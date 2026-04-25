package com.company;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

// Begin code changes by Walter Morris.
// Task 1 scheduler simulation entry point and helpers.
public class Task1_Main {

    // Limits from the project specification.
    private static final int MIN_TASKS = 1;
    private static final int MAX_TASKS = 25;
    private static final int MIN_BURST = 1;
    private static final int MAX_BURST = 50;

    // Duration of one simulated CPU cycle.
    // Kept short so runs complete quickly.
    private static final long CLOCK_CYCLE_MS = 10L;

    public static void main(String[] args) {
        Config config;
        try {
            // Parse command-line options.
            config = Config.parse(args);
        } catch (IllegalArgumentException ex) {
            System.err.println("Error: " + ex.getMessage());
            Config.printUsage();
            return;
        }

        if (config.helpRequested) {
            Config.printUsage();
            return;
        }

        // Use a seed when provided so results are reproducible.
        Random rng = (config.seed == null) ? new Random() : new Random(config.seed);

        // If -T is omitted, choose a random task count in range.
        int taskCount;
        if (config.taskCount == null) {
            taskCount = randomInclusive(rng, MIN_TASKS, MAX_TASKS);
        } else {
            taskCount = config.taskCount;
        }

        // Build burst and arrival values for each task.
        List<Integer> bursts = buildBurstList(config, rng, taskCount);
        List<Integer> arrivals = buildArrivalList(config, rng, bursts.size());

        List<SimTask> tasks = new ArrayList<>();

        // Create task threads.
        for (int i = 0; i < bursts.size(); i++) {
            int taskId = i + 1;
            SimTask task = new SimTask(taskId, bursts.get(i), arrivals.get(i));
            tasks.add(task);
            // Spec requirement: print thread creation.
            System.out.printf("[Main] Created Task %d | burst=%d | arrival=%d%n",
                    taskId, task.getTotalBurst(), task.getArrivalTick());
        }

        System.out.printf("[Main] Algorithm=%s%n", config.algorithm.label);
        if (config.algorithm == SchedulingAlgorithm.RR) {
            System.out.printf("[Main] RR quantum=%d%n", config.quantum);
        }
        System.out.printf("[Main] Task count=%d%n", tasks.size());

        // Start tasks first so they can block on their monitor before dispatch begins.
        for (SimTask task : tasks) {
            task.start();
        }

        // Start dispatcher thread.
        Dispatcher dispatcher = new Dispatcher(config.algorithm, config.quantum, tasks);
        dispatcher.start();

        // Wait for dispatcher and task threads to finish.
        try {
            dispatcher.join(); // Wait for dispatcher
            for (SimTask task : tasks) {
                task.join();   // Wait for all task threads
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            System.err.println("Simulation interrupted.");
            return;
        }

        System.out.println("[Main] All tasks completed. Exiting.");
    }

    // Return a random integer in [min, max].
    private static int randomInclusive(Random rng, int min, int max) {
        return min + rng.nextInt(max - min + 1);
    }

    // Build burst list from -B or generate random values.
    private static List<Integer> buildBurstList(Config config, Random rng, int taskCount) {
        if (config.fixedBursts != null) {
            return config.fixedBursts; // User provided explicit bursts
        }

        List<Integer> bursts = new ArrayList<>();
        for (int i = 0; i < taskCount; i++) {
            bursts.add(randomInclusive(rng, MIN_BURST, MAX_BURST));
        }
        return bursts;
    }

    // Build arrival times.
    // FCFS, RR, and NSJF all start at tick 0; PSJF may include late arrivals.
    private static List<Integer> buildArrivalList(Config config, Random rng, int taskCount) {
        List<Integer> arrivals = new ArrayList<>();

        if (config.algorithm != SchedulingAlgorithm.PSJF) {
            for (int i = 0; i < taskCount; i++) {
                arrivals.add(0); // All tasks start at tick 0
            }
            return arrivals;
        }

        // For PSJF, tasks can arrive over time.
        boolean hasLateArrival = false;
        for (int i = 0; i < taskCount; i++) {
            int arrival = randomInclusive(rng, 0, config.psjfMaxArrivalTick);
            arrivals.add(arrival);
            if (arrival > 0) {
                hasLateArrival = true;
            }
        }

        // Ensure at least one task arrives at tick 0 so the CPU can start.
        if (!arrivals.isEmpty()) {
            arrivals.set(0, 0);
        }

        // Ensure there is at least one late arrival to exercise preemption.
        if (!hasLateArrival && taskCount > 1) {
            int index = randomInclusive(rng, 1, taskCount - 1);
            arrivals.set(index, Math.max(1, config.psjfMaxArrivalTick / 2));
        }

        return arrivals;
    }

    // Supported scheduling algorithms.
    private enum SchedulingAlgorithm {
        FCFS(1, "FCFS"),
        RR(2, "RR"),
        NSJF(3, "NSJF"),
        PSJF(4, "PSJF");

        private final int code;
        private final String label;

        SchedulingAlgorithm(int code, String label) {
            this.code = code;
            this.label = label;
        }

        private static SchedulingAlgorithm fromCode(int code) {
            for (SchedulingAlgorithm value : values()) {
                if (value.code == code) {
                    return value;
                }
            }
            return null;
        }
    }

    // Parses and validates command-line arguments.
    private static class Config {

        private SchedulingAlgorithm algorithm;
        private Integer quantum;
        private Integer coreCount = 1;
        private Integer taskCount;
        private List<Integer> fixedBursts;
        private Integer psjfMaxArrivalTick = 12;
        private Long seed;
        private boolean helpRequested;

        private static Config parse(String[] args) {
            Config config = new Config();

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "-S": {
                        requireNext(args, i, "Missing algorithm code after -S");
                        int algorithmCode = parseIntegerInRange(args[++i], 1, 4, "algorithm code for -S");
                        SchedulingAlgorithm schedulingAlgorithm = SchedulingAlgorithm.fromCode(algorithmCode);
                        if (schedulingAlgorithm == null) {
                            throw new IllegalArgumentException("Unknown algorithm code: " + algorithmCode);
                        }
                        config.algorithm = schedulingAlgorithm;

                        // RR requires a quantum after -S 2.
                        if (schedulingAlgorithm == SchedulingAlgorithm.RR) {
                            requireNext(args, i, "Missing RR time quantum after -S 2");
                            config.quantum = parseIntegerInRange(args[++i], 2, 10, "RR quantum");
                        }
                        break;
                    }
                    case "-C": {
                        requireNext(args, i, "Missing core count after -C");
                        config.coreCount = parseIntegerInRange(args[++i], 1, 4, "core count for -C");
                        break;
                    }
                    case "-T": {
                        requireNext(args, i, "Missing task count after -T");
                        config.taskCount = parseIntegerInRange(args[++i], MIN_TASKS, MAX_TASKS, "task count for -T");
                        break;
                    }
                    case "-B": {
                        requireNext(args, i, "Missing burst list after -B");
                        config.fixedBursts = parseBurstList(args[++i]);
                        break;
                    }
                    case "-A": {
                        requireNext(args, i, "Missing max arrival tick after -A");
                        config.psjfMaxArrivalTick = parseIntegerInRange(args[++i], 1, 1000, "PSJF max arrival tick");
                        break;
                    }
                    case "--seed": {
                        requireNext(args, i, "Missing numeric seed after --seed");
                        config.seed = parseLong(args[++i], "seed");
                        break;
                    }
                    case "-h":
                    case "--help": {
                        config.helpRequested = true;
                        break;
                    }
                    default:
                        throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            if (config.helpRequested) {
                return config;
            }

            if (config.algorithm == null) {
                throw new IllegalArgumentException("Scheduling algorithm is required. Use -S <1|2|3|4>.");
            }

            if (config.algorithm != SchedulingAlgorithm.RR && config.quantum != null) {
                throw new IllegalArgumentException("RR quantum should only be provided with -S 2.");
            }

            if (config.coreCount != 1) {
                throw new IllegalArgumentException("This implementation is for Task 1 (single core). Use -C 1.");
            }

            if (config.fixedBursts != null) {
                if (config.taskCount != null && !config.taskCount.equals(config.fixedBursts.size())) {
                    throw new IllegalArgumentException("-T must match the number of burst values in -B.");
                }
                config.taskCount = config.fixedBursts.size();
            }

            return config;
        }

        // Validate that an option has a following value.
        private static void requireNext(String[] args, int currentIndex, String message) {
            if (currentIndex + 1 >= args.length) {
                throw new IllegalArgumentException(message);
            }
        }

        private static int parseIntegerInRange(String text, int min, int max, String fieldName) {
            int value;
            try {
                value = Integer.parseInt(text);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid integer for " + fieldName + ": " + text);
            }
            if (value < min || value > max) {
                throw new IllegalArgumentException(fieldName + " must be in [" + min + ", " + max + "]");
            }
            return value;
        }

        private static long parseLong(String text, String fieldName) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid long value for " + fieldName + ": " + text);
            }
        }

        private static List<Integer> parseBurstList(String burstListText) {
            String[] parts = burstListText.split(",");
            if (parts.length == 0) {
                throw new IllegalArgumentException("Burst list cannot be empty.");
            }
            if (parts.length > MAX_TASKS) {
                throw new IllegalArgumentException("Burst list cannot have more than " + MAX_TASKS + " values.");
            }

            List<Integer> bursts = new ArrayList<>();
            for (String part : parts) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    throw new IllegalArgumentException("Burst list contains an empty value.");
                }
                bursts.add(parseIntegerInRange(trimmed, MIN_BURST, MAX_BURST, "burst value"));
            }
            return bursts;
        }

        private static void printUsage() {
            System.out.println("Usage: java -cp Task_1_2\\bin com.company.Task1_Main -S <1|2|3|4> [rrQuantumIfS2] [-C <cores>] [-T <tasks>] [-B <b1,b2,...>] [-A <maxArrivalTick>] [--seed <n>]");
            System.out.println("  -S 1             FCFS");
            System.out.println("  -S 2 <2-10>      RR with quantum");
            System.out.println("  -S 3             NSJF");
            System.out.println("  -S 4             PSJF");
            System.out.println("  -C <1-4>         Core count (Task 1 requires -C 1 or omission)");
            System.out.println("  -T <1-25>        Fixed number of tasks");
            System.out.println("  -B <list>        Comma-separated burst list (each 1-50)");
            System.out.println("  -A <1-1000>      Max random arrival tick for PSJF");
            System.out.println("  --seed <n>       Seed for reproducible randomness");
        }
    }

    // Task thread: waits for dispatch, runs granted cycles, then reports completion.
    private static class SimTask extends Thread {

        private final int taskId;
        private final int totalBurst;
        private final int arrivalTick;

        // Monitor used to pause/resume this task safely.
        private final Object monitor = new Object();
        // Volatile ensures remainingBurst updates are visible across threads.
        private volatile int remainingBurst;

        private int grantedCycles;
        private int lastExecutedCycles;
        private boolean sliceCompleted;

        SimTask(int taskId, int totalBurst, int arrivalTick) {
            super("Task-" + taskId);
            this.taskId = taskId;
            this.totalBurst = totalBurst;
            this.arrivalTick = arrivalTick;
            this.remainingBurst = totalBurst;
        }

        @Override
        public void run() {
            System.out.printf("[Task %d] Thread started.%n", taskId);

            while (true) {
                int cyclesToRun;

                // Wait here until the dispatcher grants CPU cycles.
                synchronized (monitor) {
                    while (grantedCycles == 0) {
                        try {
                            monitor.wait(); // Block until work is assigned
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            System.err.printf("[Task %d] Interrupted while waiting for CPU.%n", taskId);
                            return; // Exit if interrupted
                        }
                    }
                    cyclesToRun = grantedCycles;
                    grantedCycles = 0; // Reset for next dispatch
                }

                // Execute up to the granted number of cycles.
                int executed = 0;
                while (executed < cyclesToRun && remainingBurst > 0) {
                    try {
                        Thread.sleep(CLOCK_CYCLE_MS); // Simulate one CPU tick
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        System.err.printf("[Task %d] Interrupted while running.%n", taskId);
                        return;
                    }
                    remainingBurst--;
                    executed++;
                }

                // Signal dispatcher that this slice is complete.
                synchronized (monitor) {
                    lastExecutedCycles = executed;
                    sliceCompleted = true;
                    monitor.notifyAll();
                }

                if (remainingBurst == 0) {
                    System.out.printf("[Task %d] Completed all %d cycles.%n", taskId, totalBurst);
                    return;
                }
            }
        }

        int getTaskId() {
            return taskId;
        }

        int getTotalBurst() {
            return totalBurst;
        }

        int getArrivalTick() {
            return arrivalTick;
        }

        int getRemainingBurst() {
            return remainingBurst;
        }

        boolean isFinished() {
            return remainingBurst == 0;
        }

        // Called by CPU/dispatcher to grant cycles.
        void dispatchForCycles(int cycles) {
            if (cycles <= 0) {
                throw new IllegalArgumentException("cycles must be positive");
            }

            synchronized (monitor) {
                sliceCompleted = false;
                grantedCycles = cycles;
                monitor.notifyAll();
            }
        }

        // Called by dispatcher to wait until this task finishes its slice.
        int waitForSliceCompletion() throws InterruptedException {
            synchronized (monitor) {
                while (!sliceCompleted) {
                    monitor.wait();
                }
                return lastExecutedCycles;
            }
        }
    }

    // Dispatcher thread: manages the ready queue and scheduling decisions.
    private static class Dispatcher extends Thread {

        private final SchedulingAlgorithm algorithm;
        private final int quantum;

        // Single ready queue protected by a lock (spec requirement).
        private final Lock queueLock = new ReentrantLock();
        private final Deque<SimTask> readyQueue = new ArrayDeque<>();

        // Tasks that have not reached their arrival tick yet.
        private final List<SimTask> pendingArrivals = new ArrayList<>();

        private final int totalTasks;
        private int completedTasks;
        private int clockTick; // Master simulation clock

        Dispatcher(SchedulingAlgorithm algorithm, Integer quantum, List<SimTask> tasks) {
            super("Dispatcher");
            this.algorithm = algorithm;
            this.quantum = (quantum == null) ? 0 : quantum;
            this.totalTasks = tasks.size();

            initializeQueues(tasks);
        }

        @Override
        public void run() {
            System.out.printf("[Dispatcher] Running algorithm: %s%n", algorithm.label);
            printQueue("initial");

            while (completedTasks < totalTasks) {
                // Add tasks whose arrival time has been reached.
                enqueueArrivalsAtCurrentTime();

                // Select the next task based on the active algorithm.
                SimTask selected = selectTask();

                if (selected == null) {
                    // No ready tasks; advance one tick and try again.
                    System.out.printf("[Dispatcher][t=%d] CPU idle; waiting for next arrival.%n", clockTick);
                    tickClock(1);
                    continue;
                }

                // Determine how many cycles this dispatch should grant.
                int cyclesGranted = determineCyclesToGrant(selected);
                System.out.printf("[Dispatcher][t=%d] Selected Task %d; grant=%d cycle(s); remaining=%d%n",
                        clockTick, selected.getTaskId(), cyclesGranted, selected.getRemainingBurst());

                // Run the selected task on the simulated CPU.
                int executed = runOnCpu(selected, cyclesGranted);
                clockTick += executed; // Advance simulation clock

                // Add tasks that arrived while CPU was busy.
                enqueueArrivalsAtCurrentTime();

                // Handle completion or requeue.
                if (selected.isFinished()) {
                    completedTasks++;
                    System.out.printf("[Dispatcher][t=%d] Task %d finished (%d/%d complete).%n",
                            clockTick, selected.getTaskId(), completedTasks, totalTasks);
                } else {
                    requeueIfNeeded(selected);
                }

                printQueue("after dispatch");
            }

            System.out.printf("[Dispatcher] Finished. Total runtime=%d ticks.%n", clockTick);
        }

        // Sort by arrival time, then task ID for stable ordering.
        private void initializeQueues(List<SimTask> tasks) {
            tasks.sort(Comparator.comparingInt(SimTask::getArrivalTick).thenComparingInt(SimTask::getTaskId));

            for (SimTask task : tasks) {
                if (task.getArrivalTick() == 0) {
                    readyQueue.addLast(task);
                } else {
                    pendingArrivals.add(task);
                }
            }
        }

        // Move arrived tasks from pending to ready.
        private void enqueueArrivalsAtCurrentTime() {
            queueLock.lock();
            try {
                int index = 0;
                while (index < pendingArrivals.size()) {
                    SimTask task = pendingArrivals.get(index);
                    if (task.getArrivalTick() <= clockTick) {
                        readyQueue.addLast(task);
                        pendingArrivals.remove(index);
                        System.out.printf("[Dispatcher][t=%d] Task %d arrived and added to ready queue.%n",
                                clockTick, task.getTaskId());
                    } else {
                        index++;
                    }
                }
            } finally {
                queueLock.unlock();
            }
        }

        // Select the next task according to scheduling policy.
        private SimTask selectTask() {
            queueLock.lock();
            try {
                if (readyQueue.isEmpty()) {
                    return null;
                }

                switch (algorithm) {
                    case FCFS:
                    case RR:
                        // Use queue order.
                        return readyQueue.pollFirst();
                    case NSJF:
                    case PSJF:
                        // Choose shortest remaining burst in ready queue
                        SimTask shortest = null;
                        for (SimTask task : readyQueue) {
                            if (shortest == null || task.getRemainingBurst() < shortest.getRemainingBurst()) {
                                shortest = task;
                            }
                        }
                        if (shortest != null) {
                            readyQueue.remove(shortest);
                        }
                        return shortest;
                    default:
                        throw new IllegalStateException("Unsupported algorithm: " + algorithm);
                }
            } finally {
                queueLock.unlock();
            }
        }

        // Determine dispatch length for the selected task.
        private int determineCyclesToGrant(SimTask task) {
            switch (algorithm) {
                case FCFS:
                case NSJF:
                    // Non-preemptive: run to completion
                    return task.getRemainingBurst();
                case RR:
                    // Time-sliced by quantum
                    return Math.min(quantum, task.getRemainingBurst());
                case PSJF:
                    // One tick at a time for preemption checks
                    return 1;
                default:
                    throw new IllegalStateException("Unsupported algorithm: " + algorithm);
            }
        }

        // CPU function kept separate from scheduling decisions.
        private int runOnCpu(SimTask task, int cyclesGranted) {
            System.out.printf("[CPU][t=%d] Running Task %d for up to %d cycle(s).%n",
                    clockTick, task.getTaskId(), cyclesGranted);

            // Wake task and grant cycles.
            task.dispatchForCycles(cyclesGranted);

            int executed;
            try {
                // Wait for the task to finish its granted slice.
                executed = task.waitForSliceCompletion();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Dispatcher interrupted while waiting for task", ex);
            }

            System.out.printf("[CPU][t=%d] Task %d executed %d cycle(s); remaining=%d%n",
                    clockTick, task.getTaskId(), executed, task.getRemainingBurst());

            return executed;
        }

        // Requeue unfinished task based on algorithm.
        private void requeueIfNeeded(SimTask task) {
            queueLock.lock();
            try {
                // RR and PSJF use round-robin style requeue.
                if (algorithm == SchedulingAlgorithm.RR || algorithm == SchedulingAlgorithm.PSJF) {
                    readyQueue.addLast(task);
                } else {
                    // FCFS/NSJF should rarely requeue, but keep behavior defined.
                    readyQueue.addFirst(task);
                }
            } finally {
                queueLock.unlock();
            }
        }

        // Advance time while CPU is idle.
        private void tickClock(int ticks) {
            for (int i = 0; i < ticks; i++) {
                try {
                    Thread.sleep(CLOCK_CYCLE_MS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Dispatcher interrupted while idling", ex);
                }
                clockTick++;
            }
        }

        // Print ready queue contents for trace/debug output.
        private void printQueue(String context) {
            queueLock.lock();
            try {
                StringBuilder sb = new StringBuilder();
                for (SimTask task : readyQueue) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append("T")
                            .append(task.getTaskId())
                            .append("(r=")
                            .append(task.getRemainingBurst())
                            .append(")");
                }
                if (sb.length() == 0) {
                    sb.append("empty");
                }
                System.out.printf("[Dispatcher][t=%d] ReadyQueue (%s): %s%n", clockTick, context, sb);
            } finally {
                queueLock.unlock();
            }
        }
    }
}
// End code changes by Walter Morris.
