import java.util.*;
import java.util.concurrent.Semaphore;  //semaphores for thread sync.
import java.util.concurrent.ThreadLocalRandom; //Random number generator
import java.util.concurrent.atomic.AtomicInteger; //to track completed tasks across multiple threads
import java.util.concurrent.locks.ReentrantLock; //to protect shared resources

public class Task2_Main { //Multi-core CPU scheduling simulation

    // Begin code changes by Reem.
    enum Algorithm { //stores the scheduling algorithms supported in Task 2
        FCFS, RR, NSJF
    }

    static class Config { //helper class to store configuration values
        Algorithm algorithm;
        int quantum = 0; //stores RR time quantum
        int cores = 1; //number of CPU cores, 1 is set as default
    }

    static class ProcessTask extends Thread {   //represents a process thread that runs bursts when assigned to a CPU core
        private final int processId; //unique ID of the process
        private final int maxBurst; //total burst time needed by the process
        private final Semaphore startSemaphore = new Semaphore(0);  //blocks the task until the CPU core signals it to start running
        private final Semaphore finishedSemaphore = new Semaphore(0);   //used by the task to notify the CPU core when its assigned work is complete
        private final ReentrantLock taskLock = new ReentrantLock(); //protects task assignment data

        private volatile int currentBurst = 0;  //tracks completed bursts
        private volatile int allottedBurst = 0; //stores allowed bursts for current execution
        private volatile int assignedCpu = -1; //stores CPU currently assigned to this task
        private volatile boolean terminate = false; //signals the task to terminate

        ProcessTask(int processId, int maxBurst) {  //constructor to create process thread ID and its burst
            this.processId = processId;
            this.maxBurst = maxBurst;
            setName("ProcessTask-" + processId); //sets a readable thread name
        }

        public int getProcessId() { //returns task ID
            return processId;
        }

        public int getMaxBurst() {  //returns total bursts needed
            return maxBurst;
        }

        public int getCurrentBurst() {  //returns completed burst count
            return currentBurst;
        }

        public int getRemainingBurst() {    //returns how many bursts are left
            return maxBurst - currentBurst;
        }

        public boolean isFinished() {   //checks whether the task is completely done
            return currentBurst >= maxBurst;
        }

        public void assignRun(int cpuId, int burstToRun) {  //assigns CPU ID and the burst slice to run
            taskLock.lock();
            try {
                assignedCpu = cpuId;
                allottedBurst = burstToRun;
            } finally {
                taskLock.unlock();
            }
        }

        public void signalRun() {   //wakes up the task so it can start running
            startSemaphore.release();
        }

        public void waitUntilFinishedSlice() throws InterruptedException {  //waits for the task to finish its assigned burst slice
            finishedSemaphore.acquire();
        }

        public void requestShutdown() { //sets the terminate flag and wakes task if blocked
            terminate = true;
            startSemaphore.release();
        }

        @Override
        public void run() { //the life cycle of each task thread - main execution loop
            try {
                while (true) {
                    startSemaphore.acquire();   //barrier, task waits here until CPU lets it run

                    if (terminate) {
                        break;
                    }

                    //temporary variables
                    int localCpu;
                    int localBurst;

                    taskLock.lock();  //reads assigned CPU and burst count safely
                    try {
                        localCpu = assignedCpu;
                        localBurst = allottedBurst;
                    } finally {
                        taskLock.unlock();
                    }

                    int burstGoal = Math.min(localBurst, getRemainingBurst());  //task does not run past its remaining burst time

                    System.out.printf(  //print task status before running
                            "Proc. Thread %-2d | On CPU: MB=%d, CB=%d, BT=%d, BG:=%d%n",
                            processId, maxBurst, currentBurst, burstGoal, currentBurst + burstGoal
                    );

                    for (int i = 0; i < burstGoal; i++) {   //runs an iteration for each burst cycle assigned
                        currentBurst++;
                        System.out.printf(
                                "Proc. Thread %-2d | Using CPU %d; On burst %d.%n",
                                processId, localCpu, currentBurst
                        );
                    }

                    finishedSemaphore.release();    //signals CPU that the process is done with this slice
                }
            } catch (InterruptedException e) {  //handles thread interruption
                Thread.currentThread().interrupt();
            }
        }
    }

    static class SchedulerState { //stores shared scheduler data used by dispatchers and CPU cores
        private final LinkedList<ProcessTask> readyQueue = new LinkedList<>(); //shared ready queue of runnable tasks
        private final ReentrantLock queueLock = new ReentrantLock(true); //protects access to the ready queue
        private final ReentrantLock printLock = new ReentrantLock(true); //keeps ready queue printing neat and synchronized

        private final AtomicInteger completedTasks = new AtomicInteger(0); //counts how many tasks have fully finished
        private final int totalTasks; //total number of tasks created at the start
        private final Algorithm algorithm; //selected scheduling algorithm
        private final int quantum; //RR time quantum

        SchedulerState(int totalTasks, Algorithm algorithm, int quantum) { //constructor for scheduler shared state
            this.totalTasks = totalTasks;
            this.algorithm = algorithm;
            this.quantum = quantum;
        }

        public void addTask(ProcessTask task) { //adds a task to the ready queue if it is not finished
            queueLock.lock();
            try {
                if (!task.isFinished()) {
                    readyQueue.add(task);
                }
            } finally {
                queueLock.unlock();
            }
        }

        public void requeueTask(ProcessTask task) { //puts unfinished RR task back at end of ready queue
            queueLock.lock();
            try {
                if (!task.isFinished()) {
                    readyQueue.addLast(task);
                }
            } finally {
                queueLock.unlock();
            }
        }

        public ProcessTask selectNextTask() { //selects the next task based on the chosen scheduling algorithm
            queueLock.lock();
            try {
                if (readyQueue.isEmpty()) { //returns null if no task is ready
                    return null;
                }

                if (algorithm == Algorithm.FCFS || algorithm == Algorithm.RR) { //FCFS and RR take first task in queue
                    return readyQueue.removeFirst();
                }

                ProcessTask best = readyQueue.getFirst(); //start by assuming the first task is the shortest
                for (ProcessTask task : readyQueue) { //searches queue for shortest remaining burst
                    if (task.getRemainingBurst() < best.getRemainingBurst()) {
                        best = task;
                    } else if (task.getRemainingBurst() == best.getRemainingBurst()
                            && task.getProcessId() < best.getProcessId()) {
                        best = task; //tie breaker: smaller process ID wins
                    }
                }
                readyQueue.remove(best); //remove selected task from queue
                return best;
            } finally {
                queueLock.unlock();
            }
        }

        public boolean allWorkFinished() { //checks whether all tasks have completed
            return completedTasks.get() >= totalTasks;
        }

        public void markCompleted() { //increments number of completed tasks
            completedTasks.incrementAndGet();
        }

        public int getCompletedTasks() { //returns completed task count
            return completedTasks.get();
        }

        public int getQuantum() { //returns RR quantum
            return quantum;
        }

        public void printReadyQueue() { //prints all tasks currently in ready queue
            printLock.lock();
            queueLock.lock();
            try {
                System.out.println();
                System.out.println("--------------- Ready Queue ---------------");
                for (ProcessTask task : readyQueue) {
                    System.out.printf(
                            "ID:%d, Max Burst:%d, Current Burst:%d%n",
                            task.getProcessId(), task.getMaxBurst(), task.getCurrentBurst()
                    );
                }
                System.out.println("-------------------------------------------");
                System.out.println();
            } finally {
                queueLock.unlock();
                printLock.unlock();
            }
        }

        public String algorithmLabel() { //returns printable label for selected scheduling algorithm
            return switch (algorithm) {
                case FCFS -> "FCFS";
                case RR -> "RR";
                case NSJF -> "Non Preemptive - Shortest Job First";
            };
        }
    }

    static class CPUCore extends Thread { //represents one CPU core that runs assigned tasks
        private final int cpuId; //ID number of this CPU core
        private final SchedulerState state; //shared scheduler state
        private final Semaphore coreStart = new Semaphore(0); //core waits here until dispatcher assigns work
        private final Semaphore dispatcherDone = new Semaphore(0); //used to notify dispatcher when core is finished

        private volatile ProcessTask assignedTask; //task currently assigned to this core
        private volatile int assignedBurst; //burst slice assigned to current task
        private volatile boolean shutdown = false; //signals core thread to stop

        CPUCore(int cpuId, SchedulerState state) { //constructor for CPU core
            this.cpuId = cpuId;
            this.state = state;
            setName("CPUCore-" + cpuId); //sets readable CPU thread name
        }

        public int getCpuId() { //returns core ID
            return cpuId;
        }

        public void assign(ProcessTask task, int burst) { //assigns a task and burst amount to this core
            assignedTask = task;
            assignedBurst = burst;
        }

        public void wakeUp() { //wakes up the core so it can run assigned task
            coreStart.release();
        }

        public void awaitCompletion() throws InterruptedException { //dispatcher waits here until core finishes
            dispatcherDone.acquire();
        }

        public void shutdownCore() { //signals core to shut down and wakes it if blocked
            shutdown = true;
            coreStart.release();
        }

        @Override
        public void run() { //main loop for CPU core thread
            try {
                while (true) {
                    coreStart.acquire(); //wait until dispatcher gives this core work

                    if (shutdown) {
                        break;
                    }

                    ProcessTask task = assignedTask; //store assigned task locally
                    if (task == null) { //if no task was assigned, notify dispatcher and continue
                        dispatcherDone.release();
                        continue;
                    }

                    task.assignRun(cpuId, assignedBurst); //tell task which CPU and burst slice it has
                    task.signalRun(); //allow task to start running
                    task.waitUntilFinishedSlice(); //wait until task finishes current burst slice

                    if (task.isFinished()) {
                        state.markCompleted(); //mark task done if fully completed
                    } else if (state.algorithm == Algorithm.RR) {
                        state.requeueTask(task); //RR sends unfinished task back to ready queue
                    }

                    assignedTask = null; //clear current assignment
                    dispatcherDone.release(); //notify dispatcher that core finished this round
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static class Dispatcher extends Thread { //represents one dispatcher assigned to one CPU core
        private final int dispatcherId; //dispatcher ID
        private final CPUCore cpuCore; //CPU core controlled by this dispatcher
        private final SchedulerState state; //shared scheduler state
        private final Semaphore startGate; //barrier used to start all dispatchers together

        Dispatcher(int dispatcherId, CPUCore cpuCore, SchedulerState state, Semaphore startGate) { //constructor for dispatcher
            this.dispatcherId = dispatcherId;
            this.cpuCore = cpuCore;
            this.state = state;
            this.startGate = startGate;
            setName("Dispatcher-" + dispatcherId); //sets readable dispatcher thread name
        }

        @Override
        public void run() { //main loop for dispatcher thread
            try {
                System.out.printf("Dispatcher %-2d   | Using CPU %d%n", dispatcherId, cpuCore.getCpuId()); //shows which CPU this dispatcher controls
                startGate.acquire(); //waits until all dispatchers are ready to begin

                System.out.printf("Dispatcher %-2d   | Running %s algorithm", dispatcherId, state.algorithmLabel()); //prints selected algorithm
                if (state.algorithm == Algorithm.RR) {
                    System.out.printf(", Time Quantum = %d%n", state.getQuantum()); //prints quantum for RR
                } else {
                    System.out.println();
                }

                while (!state.allWorkFinished()) { //keep selecting tasks until all work is done
                    ProcessTask task = state.selectNextTask(); //get next task from ready queue

                    if (task == null) { //if queue is empty, check if all work is done
                        if (state.allWorkFinished()) {
                            break;
                        }
                        Thread.sleep(1); //small delay before checking queue again
                        continue;
                    }

                    int burstToRun = switch (state.algorithm) { //decides how much burst this task should run
                        case RR -> Math.min(state.getQuantum(), task.getRemainingBurst()); //RR runs at most one quantum
                        case FCFS, NSJF -> task.getRemainingBurst(); //FCFS and NSJF run until task finishes
                    };

                    System.out.printf("%nDispatcher %-2d   | Running process %d%n", dispatcherId, task.getProcessId()); //prints selected process
                    cpuCore.assign(task, burstToRun); //sends selected task to its CPU core
                    cpuCore.wakeUp(); //wakes CPU core to start work
                    cpuCore.awaitCompletion(); //waits until core finishes with current task slice
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Config parseArguments(String[] args) { //reads and validates command-line arguments
        Config config = new Config(); //stores parsed settings
        boolean sawS = false; //tracks whether required -S argument was given

        for (int i = 0; i < args.length; i++) { //loop through all command-line arguments
            String arg = args[i];

            if ("-S".equals(arg)) { //checks for scheduler argument
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing parameter after -S.");
                }
                int algorithmNumber;
                try {
                    algorithmNumber = Integer.parseInt(args[++i]); //reads scheduler number
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Scheduling algorithm must be an integer between 1 and 3 for Task 2.");
                }

                switch (algorithmNumber) { //maps input number to algorithm type
                    case 1 -> config.algorithm = Algorithm.FCFS;
                    case 2 -> {
                        config.algorithm = Algorithm.RR;
                        if (i + 1 >= args.length) { //RR needs another parameter for quantum
                            throw new IllegalArgumentException("Round Robin requires a time quantum after -S 2.");
                        }
                        try {
                            config.quantum = Integer.parseInt(args[++i]); //reads RR time quantum
                        } catch (NumberFormatException e) {
                            throw new IllegalArgumentException("Round Robin time quantum must be an integer between 2 and 10.");
                        }
                        if (config.quantum < 2 || config.quantum > 10) { //validates RR quantum range
                            throw new IllegalArgumentException("Round Robin time quantum must be between 2 and 10.");
                        }
                    }
                    case 3 -> config.algorithm = Algorithm.NSJF;
                    case 4 -> throw new IllegalArgumentException("PSJF is not required for Task 2. Use -S 1, -S 2 <quantum>, or -S 3.");
                    default -> throw new IllegalArgumentException("Invalid scheduling algorithm. Use 1, 2, or 3 for Task 2.");
                }
                sawS = true;
            } else if ("-C".equals(arg)) { //checks for core count argument
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing parameter after -C.");
                }
                try {
                    config.cores = Integer.parseInt(args[++i]); //reads number of cores
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Core count must be an integer between 1 and 4.");
                }
                if (config.cores < 1 || config.cores > 4) { //validates core count range
                    throw new IllegalArgumentException("Core count must be between 1 and 4.");
                }
            } else {
                throw new IllegalArgumentException("Unknown argument: " + arg); //invalid argument case
            }
        }

        if (!sawS) { //makes sure scheduling algorithm was provided
            throw new IllegalArgumentException("Missing required -S argument.");
        }

        return config; //returns validated config object
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  java Task2_Main -S <algorithm> [-C <cores>]");
        System.err.println();
        System.err.println("Algorithm options for -S:");
        System.err.println("  -S 1           FCFS");
        System.err.println("  -S 2 <2-10>    RR with required time quantum");
        System.err.println("  -S 3           NSJF");
        System.err.println();
        System.err.println("Core options:");
        System.err.println("  -C <1-4>       Number of CPU cores (default 1)");
        System.err.println();
        System.err.println("Examples:");
        System.err.println("  java Task2_Main -S 1 -C 4");
        System.err.println("  java Task2_Main -S 2 5 -C 4");
        System.err.println("  java Task2_Main -S 3 -C 2");
    }

    private static String coreLabel(int cores) { //returns readable core label for output
        return switch (cores) {
            case 1 -> "single core";
            case 2 -> "dual core";
            case 3 -> "triple core";
            case 4 -> "quad core";
            default -> cores + " cores";
        };
    }

    private static void printConfig(Config config) { //prints selected algorithm and number of cores
        switch (config.algorithm) {
            case FCFS -> System.out.println("Scheduler Algorithm Select: FCFS");
            case RR -> System.out.println("Scheduler Algorithm Select: Round Robin. Time Quantum = " + config.quantum);
            case NSJF -> System.out.println("Scheduler Algorithm Select: Non Preemptive - Shortest Job First");
        }
        System.out.println("Core Count Select: " + coreLabel(config.cores));
    }

    public static void main(String[] args) { //main method that sets up and runs the full simulation
        try {
            Config config = parseArguments(args); //reads command-line input
            printConfig(config); //prints selected settings

            int threadCount = ThreadLocalRandom.current().nextInt(4, 9); //randomly generates number of process threads
            System.out.println("# threads = " + threadCount);

            SchedulerState state = new SchedulerState(threadCount, config.algorithm, config.quantum); //creates shared scheduler state
            List<ProcessTask> tasks = new ArrayList<>(); //stores all process threads

            for (int i = 0; i < threadCount; i++) { //creates all process threads with random burst times
                int burst = ThreadLocalRandom.current().nextInt(4, 16);
                System.out.printf("Main thread     | Creating process thread %d%n", i);
                ProcessTask task = new ProcessTask(i, burst);
                tasks.add(task);
                state.addTask(task); //adds process to ready queue
            }

            state.printReadyQueue(); //prints initial ready queue

            for (ProcessTask task : tasks) {
                task.start(); //starts all process threads
            }

            Semaphore dispatcherStartGate = new Semaphore(0); //barrier to release dispatchers together
            List<CPUCore> cores = new ArrayList<>(); //stores CPU core threads
            List<Dispatcher> dispatchers = new ArrayList<>(); //stores dispatcher threads

            for (int i = 0; i < config.cores; i++) { //creates one CPU core and one dispatcher per core
                CPUCore core = new CPUCore(i, state);
                Dispatcher dispatcher = new Dispatcher(i, core, state, dispatcherStartGate);
                cores.add(core);
                dispatchers.add(dispatcher);
            }

            for (CPUCore core : cores) {
                core.start(); //starts all CPU core threads
            }

            for (int i = 0; i < dispatchers.size(); i++) {
                System.out.printf("Main thread     | Forking dispatcher %d%n", i);
                dispatchers.get(i).start(); //starts all dispatcher threads
            }

            Thread.sleep(10); //small delay so all dispatchers are ready
            System.out.println("Dispatcher " + (dispatchers.size() - 1) + "    | Now releasing dispatchers.");
            dispatcherStartGate.release(dispatchers.size()); //releases all dispatchers at once

            for (Dispatcher dispatcher : dispatchers) {
                dispatcher.join(); //waits for all dispatchers to finish
            }

            for (CPUCore core : cores) {
                core.shutdownCore(); //signals all cores to stop
            }
            for (CPUCore core : cores) {
                core.join(); //waits for all core threads to finish
            }

            for (ProcessTask task : tasks) {
                task.requestShutdown(); //signals all process threads to stop
            }
            for (ProcessTask task : tasks) {
                task.join(); //waits for all task threads to finish
            }

            System.out.println();
            System.out.println("Main thread     | Exiting."); //final exit message
        } catch (IllegalArgumentException e) { //handles invalid command-line input
            System.err.println("Error: " + e.getMessage());
            printUsage();
        } catch (InterruptedException e) { //handles thread interruption safely
            Thread.currentThread().interrupt();
            System.err.println("Program interrupted.");
        }
    }
    // End code changes by Reem.
}
