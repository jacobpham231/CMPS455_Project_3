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
// This implementation focuses on Task 1 requirements (single core, no I/O).
public class Main {

    // Setting up the boundaries defined by the project specs
    private static final int MIN_TASKS = 1;
    private static final int MAX_TASKS = 25;
    private static final int MIN_BURST = 1;
    private static final int MAX_BURST = 50;
    
    // This represents the time passing for ONE CPU cycle. 
    // Kept short (10ms) so the simulation doesn't take forever to run.
    private static final long CLOCK_CYCLE_MS = 10L;

    public static void main(String[] args) {
        Config config;
        try {
            // First things first, parse those command line arguments (-S, -C, etc.)
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

        // Initialize our random number generator. If a seed is provided (good for testing), use it.
        Random rng = (config.seed == null) ? new Random() : new Random(config.seed);

        // Figure out how many tasks we are making. If the user didn't specify with -T, 
        // we pick a random number between 1 and 25.
        int taskCount;
        if (config.taskCount == null) {
            taskCount = randomInclusive(rng, MIN_TASKS, MAX_TASKS);
        } else {
            taskCount = config.taskCount;
        }

        // Generate the random bursts and arrival times for our tasks
        List<Integer> bursts = buildBurstList(config, rng, taskCount);
        List<Integer> arrivals = buildArrivalList(config, rng, bursts.size());

        List<SimTask> tasks = new ArrayList<>();
        
        // Let's actually create the threads now.
        for (int i = 0; i < bursts.size(); i++) {
            int taskId = i + 1;
            SimTask task = new SimTask(taskId, bursts.get(i), arrivals.get(i));
            tasks.add(task);
            // Spec requirement: Print creation of new threads
            System.out.printf("[Main] Created Task %d | burst=%d | arrival=%d%n",
                    taskId, task.getTotalBurst(), task.getArrivalTick());
        }

        System.out.printf("[Main] Algorithm=%s%n", config.algorithm.label);
        if (config.algorithm == SchedulingAlgorithm.RR) {
            System.out.printf("[Main] RR quantum=%d%n", config.quantum);
        }
        System.out.printf("[Main] Task count=%d%n", tasks.size());

        // We have to start the threads BEFORE the dispatcher so they are alive and waiting 
        // in their `wait()` monitors for the CPU to give them cycles.
        for (SimTask task : tasks) {
            task.start();
        }

        // Fire up the dispatcher!
        Dispatcher dispatcher = new Dispatcher(config.algorithm, config.quantum, tasks);
        dispatcher.start();

        // The main thread just hangs out here and waits for everything to finish up gracefully.
        try {
            dispatcher.join(); // Wait for dispatcher
            for (SimTask task : tasks) {
                task.join();   // Wait for all tasks to officially die
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            System.err.println("Simulation interrupted.");
            return;
        }

        System.out.println("[Main] All tasks completed. Exiting.");
    }

    // Helper to get a random number inclusive of the min and max
    private static int randomInclusive(Random rng, int min, int max) {
        return min + rng.nextInt(max - min + 1);
    }

    // Builds our list of burst times. Handles both random generation and fixed user input (-B)
    private static List<Integer> buildBurstList(Config config, Random rng, int taskCount) {
        if (config.fixedBursts != null) {
            return config.fixedBursts; // User gave us specific bursts
        }

        List<Integer> bursts = new ArrayList<>();
        for (int i = 0; i < taskCount; i++) {
            bursts.add(randomInclusive(rng, MIN_BURST, MAX_BURST));
        }
        return bursts;
    }

    // Handles arrival times. Remember, for FCFS, RR, and NSJF they all arrive at time 0.
    // For PSJF, they need to arrive randomly AFTER things start running.
    private static List<Integer> buildArrivalList(Config config, Random rng, int taskCount) {
        List<Integer> arrivals = new ArrayList<>();

        if (config.algorithm != SchedulingAlgorithm.PSJF) {
            for (int i = 0; i < taskCount; i++) {
                arrivals.add(0); // Everything starts at tick 0
            }
            return arrivals;
        }

        // PSJF logic: Tasks trickle in over time
        boolean hasLateArrival = false;
        for (int i = 0; i < taskCount; i++) {
            int arrival = randomInclusive(rng, 0, config.psjfMaxArrivalTick);
            arrivals.add(arrival);
            if (arrival > 0) {
                hasLateArrival = true;
            }
        }

        // Make sure at least one task arrives at time 0 so the CPU has something to start with
        if (!arrivals.isEmpty()) {
            arrivals.set(0, 0);
        }

        // Guarantee we actually test preemption by forcing a late arrival if rng was weird
        if (!hasLateArrival && taskCount > 1) {
            int index = randomInclusive(rng, 1, taskCount - 1);
            arrivals.set(index, Math.max(1, config.psjfMaxArrivalTick / 2));
        }

        return arrivals;
    }

    // Simple enum to keep our algorithms organized
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

    // This nested class strictly handles the messy command line stuff
    // Keeping it contained here is great practice so it doesn't clutter main()
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

                        // Only grab the quantum if they picked Round Robin (2)
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

            if (config.helpRequested) return config;

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

        // Helper to prevent ArrayOutOfBounds if user types "-S" and hits enter
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
            System.out.println("Usage: java com.company.Main -S <algorithm> [quantum] [-C <cores>] [-T <tasks>] [-B <b1,b2,...>] [-A <maxArrivalTick>] [--seed <n>]");
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

    // --- THIS IS THE TASK THREAD ---
    // Modeled to run a loop where each iteration is a CPU cycle.
    private static class SimTask extends Thread {

        private final int taskId;
        private final int totalBurst;
        private final int arrivalTick;

        // monitor is the locking object used to pause/resume this thread safely
        private final Object monitor = new Object();
        // volatile ensures that changes to remainingBurst are immediately visible to the dispatcher
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

                // 1. SLEEP PHASE: The thread waits here until the Dispatcher gives it cycles
                synchronized (monitor) {
                    while (grantedCycles == 0) {
                        try {
                            monitor.wait(); // Pause thread execution
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            System.err.printf("[Task %d] Interrupted while waiting for CPU.%n", taskId);
                            return; // Gracefully exit if crashed
                        }
                    }
                    cyclesToRun = grantedCycles;
                    grantedCycles = 0; // Reset for next time
                }

                // 2. RUN PHASE: We got cycles! Time to execute our burst loop.
                int executed = 0;
                while (executed < cyclesToRun && remainingBurst > 0) {
                    try {
                        Thread.sleep(CLOCK_CYCLE_MS); // Simulating 1 CPU clock cycle
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        System.err.printf("[Task %d] Interrupted while running.%n", taskId);
                        return;
                    }
                    remainingBurst--;
                    executed++;
                }

                // 3. WAKE DISPATCHER PHASE: Tell the dispatcher we finished our allotted slice
                synchronized (monitor) {
                    lastExecutedCycles = executed;
                    sliceCompleted = true;
                    monitor.notifyAll(); // Wake up the waiting dispatcher
                }

                if (remainingBurst == 0) {
                    System.out.printf("[Task %d] Completed all %d cycles.%n", taskId, totalBurst);
                    return; // Thread naturally dies when work is done
                }
            }
        }

        int getTaskId() { return taskId; }
        int getTotalBurst() { return totalBurst; }
        int getArrivalTick() { return arrivalTick; }
        int getRemainingBurst() { return remainingBurst; }
        boolean isFinished() { return remainingBurst == 0; }

        // The CPU function uses this to wake the thread up and tell it how long to run
        void dispatchForCycles(int cycles) {
            if (cycles <= 0) {
                throw new IllegalArgumentException("cycles must be positive");
            }

            synchronized (monitor) {
                sliceCompleted = false;
                grantedCycles = cycles;
                monitor.notifyAll(); // Wake up the monitor.wait() up in the run() method
            }
        }

        // The CPU function uses this to pause the dispatcher while this thread does its work
        int waitForSliceCompletion() throws InterruptedException {
            synchronized (monitor) {
                while (!sliceCompleted) {
                    monitor.wait();
                }
                return lastExecutedCycles;
            }
        }
    }

    // --- THIS IS THE DISPATCHER THREAD ---
    // Manages the ready queue and figures out who runs when.
    private static class Dispatcher extends Thread {

        private final SchedulingAlgorithm algorithm;
        private final int quantum;

        // The single ready queue protected by a lock (Spec requirement)
        private final Lock queueLock = new ReentrantLock();
        private final Deque<SimTask> readyQueue = new ArrayDeque<>();
        
        // Holds tasks that haven't "arrived" yet (mostly for PSJF)
        private final List<SimTask> pendingArrivals = new ArrayList<>();

        private final int totalTasks;
        private int completedTasks;
        private int clockTick; // Master clock for the system

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
                // Check if any new tasks arrived at this exact clock tick
                enqueueArrivalsAtCurrentTime();

                // Pick the next victim based on FCFS, RR, NSJF, or PSJF rules
                SimTask selected = selectTask();
                
                if (selected == null) {
                    // Nothing in the queue, let the clock tick forward 1 and check again
                    System.out.printf("[Dispatcher][t=%d] CPU idle; waiting for next arrival.%n", clockTick);
                    tickClock(1);
                    continue;
                }

                // Figure out how much time we are giving this thread
                int cyclesGranted = determineCyclesToGrant(selected);
                System.out.printf("[Dispatcher][t=%d] Selected Task %d; grant=%d cycle(s); remaining=%d%n",
                        clockTick, selected.getTaskId(), cyclesGranted, selected.getRemainingBurst());

                // Pass to the CPU function (which is inside this thread per spec allowance)
                int executed = runOnCpu(selected, cyclesGranted);
                clockTick += executed; // Fast forward our master clock by the time spent

                // If tasks arrived while the CPU was busy, add them to the queue now
                enqueueArrivalsAtCurrentTime();

                // Did the thread finish?
                if (selected.isFinished()) {
                    completedTasks++;
                    System.out.printf("[Dispatcher][t=%d] Task %d finished (%d/%d complete).%n",
                            clockTick, selected.getTaskId(), completedTasks, totalTasks);
                } else {
                    requeueIfNeeded(selected); // Throw it back in the queue if it has remaining burst
                }

                printQueue("after dispatch");
            }

            System.out.printf("[Dispatcher] Finished. Total runtime=%d ticks.%n", clockTick);
        }

        // Sorts tasks by arrival time so pendingArrivals is ready to go
        private void initializeQueues(List<SimTask> tasks) {
            tasks.sort(Comparator.comparingInt(SimTask::getArrivalTick).thenComparingInt(SimTask::getTaskId));

            for (SimTask task : tasks) {
                if (task.getArrivalTick() == 0) {
                    readyQueue.addLast(task); // Ready right now
                } else {
                    pendingArrivals.add(task); // Will be ready later
                }
            }
        }

        // Moves tasks from pending to ready if their arrival time has hit the master clock
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

        // The brains of the scheduler. Pulls the right task based on the current algorithm.
        private SimTask selectTask() {
            queueLock.lock();
            try {
                if (readyQueue.isEmpty()) {
                    return null;
                }

                switch (algorithm) {
                    case FCFS:
                    case RR:
                        // Just grab the first thing in line
                        return readyQueue.pollFirst();
                    case NSJF:
                    case PSJF:
                        // We have to scan the queue for the absolute shortest remaining burst
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

        // Determines how long a thread gets to run before we pull it off the CPU
        private int determineCyclesToGrant(SimTask task) {
            switch (algorithm) {
                case FCFS:
                case NSJF:
                    // They run until they die
                    return task.getRemainingBurst();
                case RR:
                    // They run until they die OR hit the time quantum
                    return Math.min(quantum, task.getRemainingBurst());
                case PSJF:
                    // We run it 1 tick at a time so we can constantly check for shorter arriving tasks
                    return 1;
                default:
                    throw new IllegalStateException("Unsupported algorithm: " + algorithm);
            }
        }

        // --- THE CPU FUNCTION ---
        // Satisfies the requirement: "The CPU must be represented as a separate function from the dispatcher..."
        private int runOnCpu(SimTask task, int cyclesGranted) {
            System.out.printf("[CPU][t=%d] Running Task %d for up to %d cycle(s).%n",
                    clockTick, task.getTaskId(), cyclesGranted);

            // Wakes up the task thread and tells it how long to run
            task.dispatchForCycles(cyclesGranted);

            int executed;
            try {
                // Dispatcher goes to sleep while the task thread does the actual work
                executed = task.waitForSliceCompletion();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Dispatcher interrupted while waiting for task", ex);
            }

            System.out.printf("[CPU][t=%d] Task %d executed %d cycle(s); remaining=%d%n",
                    clockTick, task.getTaskId(), executed, task.getRemainingBurst());

            return executed;
        }

        // Puts the task back in line if it didn't finish
        private void requeueIfNeeded(SimTask task) {
            queueLock.lock();
            try {
                // RR and PSJF throw it to the back of the line
                if (algorithm == SchedulingAlgorithm.RR || algorithm == SchedulingAlgorithm.PSJF) {
                    readyQueue.addLast(task);
                } else {
                    // FCFS/NSJF technically shouldn't hit this because they run to completion, 
                    // but if they do, put them back in front.
                    readyQueue.addFirst(task);
                }
            } finally {
                queueLock.unlock();
            }
        }

        // Idles the CPU clock if nothing is ready
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

        // Helper to print the queue so the TA can see what's happening internally
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
//End code changes by Walter Morris.