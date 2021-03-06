/*
 * Copyright 2018 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.optaweb.vehiclerouting.plugin.planner;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.optaplanner.core.api.score.buildin.simplelong.SimpleLongScore;
import org.optaplanner.core.api.solver.Solver;
import org.optaplanner.core.api.solver.event.BestSolutionChangedEvent;
import org.optaplanner.core.api.solver.event.SolverEventListener;
import org.optaplanner.examples.tsp.domain.Domicile;
import org.optaplanner.examples.tsp.domain.Standstill;
import org.optaplanner.examples.tsp.domain.TspSolution;
import org.optaplanner.examples.tsp.domain.Visit;
import org.optaplanner.examples.tsp.domain.location.Location;
import org.optaplanner.examples.tsp.domain.location.RoadLocation;
import org.optaweb.vehiclerouting.domain.LatLng;
import org.optaweb.vehiclerouting.service.location.DistanceMatrix;
import org.optaweb.vehiclerouting.service.location.RouteOptimizer;
import org.optaweb.vehiclerouting.service.route.RouteChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class RouteOptimizerImpl implements RouteOptimizer,
                                           SolverEventListener<TspSolution> {

    private static final Logger logger = LoggerFactory.getLogger(RouteOptimizerImpl.class);

    private final ApplicationEventPublisher publisher;
    private final Solver<TspSolution> solver;
    private final AsyncTaskExecutor executor;
    private Future<?> solverFuture;
    private TspSolution tsp;

    @Autowired
    public RouteOptimizerImpl(ApplicationEventPublisher publisher,
                              Solver<TspSolution> solver,
                              AsyncTaskExecutor executor) {
        this.publisher = publisher;
        this.solver = solver;
        this.executor = executor;

        this.solver.addEventListener(this);
        tsp = emptySolution();
    }

    static TspSolution emptySolution() {
        TspSolution tsp = new TspSolution();
        tsp.setLocationList(new ArrayList<>());
        tsp.setVisitList(new ArrayList<>());
        tsp.setScore(SimpleLongScore.ZERO);
        return tsp;
    }

    static RoadLocation coreToPlanner(org.optaweb.vehiclerouting.domain.Location location) {
        return new RoadLocation(location.getId(),
                location.getLatLng().getLatitude().doubleValue(),
                location.getLatLng().getLongitude().doubleValue()
        );
    }

    private static Optional<List<org.optaweb.vehiclerouting.domain.Location>> extractRoute(TspSolution tsp) {
        Map<Standstill, Visit> nextVisitMap = new LinkedHashMap<>();
        for (Visit visit : tsp.getVisitList()) {
            if (visit.getPreviousStandstill() != null) {
                nextVisitMap.put(visit.getPreviousStandstill(), visit);
            } else {
                logger.info("Ignoring a solution with an unconnected visit: {}", visit);
                return Optional.empty();
            }
        }

        // TODO race condition, if a rest thread deletes that location in the middle of this method happening on the solver thread
        // TODO make sure that location is still in the repository
        // TODO maybe repair the solution OR ignore if it's inconsistent (log WARNING)
        Domicile domicile = tsp.getDomicile();
        if (domicile == null) {
            return Optional.of(new ArrayList<>());
        }
        List<org.optaweb.vehiclerouting.domain.Location> route = new ArrayList<>();
        addLocationToRoute(route, domicile.getLocation());
        for (Visit visit = nextVisitMap.get(domicile); visit != null; visit = nextVisitMap.get(visit)) {
            addLocationToRoute(route, visit.getLocation());
        }
        return Optional.of(route);
    }

    private static void addLocationToRoute(List<org.optaweb.vehiclerouting.domain.Location> route, Location location) {
        route.add(new org.optaweb.vehiclerouting.domain.Location(
                location.getId(),
                LatLng.valueOf(location.getLatitude(), location.getLongitude())
        ));
    }

    private void publishRoute(TspSolution solution) {
        extractRoute(solution).ifPresent(route -> {
            logger.debug("New TSP with {} locations, {} visits, route: {}",
                    solution.getLocationList().size(),
                    solution.getVisitList().size(),
                    route);
            String distanceString = solution.getDistanceString(new DecimalFormat("#,##0.00"));
            publisher.publishEvent(new RouteChangedEvent(this, distanceString, route));
        });
    }

    private void startSolver() {
        if (solverFuture != null) {
            throw new IllegalStateException("Solver start has already been requested");
        }
        // TODO move this to @Async method?
        // TODO use ListenableFuture to react to solve() exceptions immediately?
        solverFuture = executor.submit(() -> {
            solver.solve(tsp);
        });
    }

    boolean isSolving() {
        if (solverFuture == null) {
            return false;
        }
        assertSolverIsAlive();
        return true;
    }

    private void assertSolverIsAlive() {
        if (solverFuture.isDone()) {
            try {
                solverFuture.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException("Solver has died", e);
            }
            throw new IllegalStateException("Solver has finished solving even though it operates in daemon mode.");
        }
    }

    void stopSolver() {
        if (solverFuture != null) {
            // TODO what happens if solver hasn't started yet (solve() is called asynchronously)
            solver.terminateEarly();
            // make sure solver has terminated and propagate exceptions
            try {
                solverFuture.get();
                solverFuture = null;
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException("Failed to stop solver", e);
            }
        }
    }

    @Override
    public void bestSolutionChanged(BestSolutionChangedEvent<TspSolution> bestSolutionChangedEvent) {
        if (!bestSolutionChangedEvent.isEveryProblemFactChangeProcessed()) {
            logger.info("Ignoring a new best solution that has some problem facts missing");
            return;
        }
        tsp = bestSolutionChangedEvent.getNewBestSolution();
        publishRoute(tsp);
    }

    @Override
    public void addLocation(org.optaweb.vehiclerouting.domain.Location coreLocation,
                            DistanceMatrix distanceMatrix) {
        RoadLocation location = coreToPlanner(coreLocation);
        DistanceMap distanceMap = new DistanceMap(coreLocation, distanceMatrix.getRow(coreLocation));
        location.setTravelDistanceMap(distanceMap);
        // Unfortunately can't start solver with an empty solution (see https://issues.jboss.org/browse/PLANNER-776)
        if (!isSolving()) {
            List<Location> locationList = tsp.getLocationList();
            locationList.add(location);
            if (locationList.size() == 1) {
                Domicile domicile = new Domicile();
                domicile.setId(location.getId());
                domicile.setLocation(location);
                tsp.setDomicile(domicile);
                publishRoute(tsp);
            } else if (locationList.size() == 2) {
                Visit visit = new Visit();
                visit.setId(location.getId());
                visit.setLocation(location);
                tsp.getVisitList().add(visit);
                startSolver();
            }
        } else {
            solver.addProblemFactChange(scoreDirector -> {
                TspSolution workingSolution = scoreDirector.getWorkingSolution();
                workingSolution.setLocationList(new ArrayList<>(workingSolution.getLocationList()));

                scoreDirector.beforeProblemFactAdded(location);
                workingSolution.getLocationList().add(location);
                scoreDirector.afterProblemFactAdded(location);

                Visit visit = new Visit();
                visit.setId(location.getId());
                visit.setLocation(location);

                scoreDirector.beforeEntityAdded(visit);
                workingSolution.getVisitList().add(visit);
                scoreDirector.afterEntityAdded(visit);

                scoreDirector.triggerVariableListeners();
            });
        }
    }

    @Override
    public void removeLocation(org.optaweb.vehiclerouting.domain.Location coreLocation) {
        Location location = coreToPlanner(coreLocation);
        if (!isSolving()) {
            if (tsp.getLocationList().size() != 1) {
                throw new IllegalStateException("Impossible number of locations (" + tsp.getLocationList().size()
                        + ") when solver is not solving.\n" + tsp.getLocationList());
            }
            tsp.getLocationList().remove(0);
            tsp.setDomicile(null);
            publishRoute(tsp);
        } else {
            if (tsp.getDomicile().getLocation().getId().equals(location.getId())) {
                throw new UnsupportedOperationException("You can only remove domicile if it's the only location on map.");
            }
            if (tsp.getVisitList().size() == 1) {
                // domicile and 1 visit remaining
                stopSolver();
                tsp.getVisitList().remove(0);
                tsp.getLocationList().removeIf(l -> l.getId().equals(location.getId()));
                publishRoute(tsp);
            } else {
                solver.addProblemFactChanges(Arrays.asList(
                        scoreDirector -> {
                            TspSolution workingSolution = scoreDirector.getWorkingSolution();
                            Visit visit = workingSolution.getVisitList().stream()
                                    .filter(v -> v.getLocation().getId().equals(location.getId()))
                                    .findFirst()
                                    .orElseThrow(() -> new IllegalArgumentException(
                                            "Invalid request for removing visit at " + location));

                            // Remove the visit
                            scoreDirector.beforeEntityRemoved(visit);
                            if (!workingSolution.getVisitList().remove(visit)) {
                                throw new IllegalStateException("This is impossible.");
                            }
                            scoreDirector.afterEntityRemoved(visit);

                            // Fix the next visit and set its previousStandstill to the removed visit's previousStandstill
                            for (Visit nextVisit : workingSolution.getVisitList()) {
                                if (nextVisit.getPreviousStandstill().equals(visit)) {
                                    scoreDirector.beforeVariableChanged(nextVisit, "previousStandstill");
                                    nextVisit.setPreviousStandstill(visit.getPreviousStandstill());
                                    scoreDirector.afterVariableChanged(nextVisit, "previousStandstill");
                                    break;
                                }
                            }

                            scoreDirector.triggerVariableListeners();
                        },
                        scoreDirector -> {
                            TspSolution workingSolution = scoreDirector.getWorkingSolution();

                            Location workingLocation = scoreDirector.lookUpWorkingObject(location);
                            if (workingLocation == null) {
                                throw new IllegalStateException("Can't look up working copy of " + location);
                            }
                            // shallow clone fact list
                            // TODO think if we can fail fast when user forgets to make the clone (PLANNER)
                            workingSolution.setLocationList(new ArrayList<>(workingSolution.getLocationList()));
                            scoreDirector.beforeProblemFactRemoved(workingLocation);
                            if (!workingSolution.getLocationList().remove(workingLocation)) {
                                throw new IllegalStateException("This is a bug.");
                            }
                            scoreDirector.afterProblemFactRemoved(workingLocation);

                            scoreDirector.triggerVariableListeners();
                        }
                ));
            }
        }
    }

    @Override
    public void clear() {
        stopSolver();
        tsp = emptySolution();
        publishRoute(tsp);
    }
}
