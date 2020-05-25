package pt.ulisboa.tecnico.cnv.custommanager.domain;

import pt.ulisboa.tecnico.cnv.custommanager.handler.LoadBalancerHandler;
import pt.ulisboa.tecnico.cnv.custommanager.service.InstanceSelector;

import java.util.Comparator;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class RunningInstanceState {

    private String _instanceId;
    // Keeps track of all requests currently being processed by this instance
    // Maps each request to the corresponding request cost

    private ConcurrentHashMap<String, Request> _processingRequests = new ConcurrentHashMap<>();

    private int _estimatedCpu;

    // TODO: how to obtain metrics for each machine on a certain interval?
    private Long _totalFieldLoads;
    // keeps track of all the fieldLoads performed since the latest AutoScaler run
    private Long _latestFieldLoads;
    // TODO: see what amount of field loads correspond to under 20% cpu
    private final Long IDLE_FIELD_LOADS = 1000L;
    private final Long OVERLOADED_FIELD_LOADS = 10000L;

    // machines can only start receiving requests once they are initialized
    private boolean _initialized;
    private boolean _shuttingDown;
    private ScheduledFuture<?> _scheduledShutdownFuture;
    private final int CHECK_SHUTDOWN_PERIOD = 30; // seconds

    // machine is terminated when it fails to healthcheck thrice
    private int _nHealthCheckStrikes;

    private static Logger _logger = Logger.getLogger(RunningInstanceState.class.getName());

    public RunningInstanceState(String instanceId) {
        _instanceId = instanceId;
        _estimatedCpu = 0;
        _initialized = false;
        _shuttingDown = false;
        _totalFieldLoads = 0L;
        _latestFieldLoads = 0L;
        _nHealthCheckStrikes = 0;
    }

    public String getInstanceId() {
        return _instanceId;
    }

    public boolean isInitialized() { return _initialized; }

    public void setInitialized() { _logger.info("SetInitialized()"); _initialized = true; }

    public boolean shuttingDown() { return _shuttingDown; }

    public boolean isIdle() { return _latestFieldLoads <= IDLE_FIELD_LOADS; }

    public boolean isOverloaded() { return _latestFieldLoads > OVERLOADED_FIELD_LOADS; }

    // should be performed at the end of receiving response to each request
    public void updateTotalFieldLoads(Long fieldLoads) {
        _totalFieldLoads += fieldLoads;
        synchronized(_latestFieldLoads) {
            _latestFieldLoads += fieldLoads;
        }     
    }

    public Long getLatestFieldLoads() { return _latestFieldLoads; }

    public void resetFieldLoads() {
        synchronized(_latestFieldLoads) {
            _latestFieldLoads = 0L;
        }
    }

    public void incrementHealthCheckStrikes() { _nHealthCheckStrikes++; }

    // if it is the first time that a machine healthchecks, then it is considered initialized
    public void resetHealthCheckStrikes() {
        _nHealthCheckStrikes = 0;
        if (_initialized == false) setInitialized();
    }

    public boolean failed() { return _nHealthCheckStrikes == 3; }

    public ConcurrentMap<String, Request> getProcessingRequests() { return _processingRequests; }

    public void addNewRequest(String query, Request request) {

        _processingRequests.put(query, request);
        _estimatedCpu += request.getCost().getCpuPercentage();
    }

    public synchronized void removeRequest(String uuid) {

        _estimatedCpu -= _processingRequests.get(uuid).getCost().getCpuPercentage();
        _processingRequests.remove(uuid);

    }

    public Long calculateRequestCostSum() {
        Long totalEstimatedCost = 0L;
        for (Request request : _processingRequests.values()) {
            totalEstimatedCost += request.getCost().getFieldLoads();
        }
        return totalEstimatedCost;
    }

    public Long getTotalFieldLoadsByPeriod() {
        return (getLatestFieldLoads() + calculateRequestCostSum());
    }

    public int getTotalCpuOccupied() {
        return _estimatedCpu;
    }

    public int getTotalCpuAvailable() { 
        if (_estimatedCpu < 100) return 100 - _estimatedCpu; 
        else return 0;
    }

    // orders from least cpu available to most cpu available
    public static final Comparator<RunningInstanceState> LEAST_CPU_AVAILABLE_COMPARATOR =
            new Comparator<RunningInstanceState>() {
        @Override
        public int compare(RunningInstanceState o1, RunningInstanceState o2) {
            return Integer.compare(o1.getTotalCpuAvailable(), o2.getTotalCpuAvailable());
        }
    };

    // orders from smaller latestFieldLoads value to biggest latestFieldLoads value
    public static final Comparator<RunningInstanceState> LEAST_LATEST_FIELD_LOADS_COMPARATOR =
            new Comparator<RunningInstanceState>() {
        @Override
        public int compare(RunningInstanceState o1, RunningInstanceState o2) {
            return o1.getTotalFieldLoadsByPeriod().compareTo(o2.getTotalFieldLoadsByPeriod());
        }
    };

    // TODO: see if it souhld be used for the loadbalancer to decide best machine to send request
    // orders from instances with smaller sum of field loads from all processing requests to
    // instances with bigger sum of field loads from all processing requests
    public static final Comparator<RunningInstanceState> LEAST_SUM_PROCESSING_FIELD_LOADS_COMPARATOR =
            new Comparator<RunningInstanceState>() {
        @Override
        public int compare(RunningInstanceState o1, RunningInstanceState o2) {
            return o1.calculateRequestCostSum().compareTo(o2.calculateRequestCostSum());
        }
    };

    public void scheduleShutdown() {
        _shuttingDown = true;
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        _scheduledShutdownFuture = scheduler.scheduleAtFixedRate(new ShutdownChecker(), 0,
                CHECK_SHUTDOWN_PERIOD, TimeUnit.SECONDS);
    }

    public class ShutdownChecker implements Runnable {
        @Override
        public void run() {
            _logger.info("Checking if it is possible to shutdown instance " + _instanceId);
            // instance only shutdowns when there are no requests to fulfill
            if (_processingRequests.size() == 0) {
                _logger.info("Scheduled shutting down instance " + _instanceId);
                _scheduledShutdownFuture.cancel(false);
                InstanceSelector.getInstance().terminateInstance(_instanceId);
            }
        }
    }
}
