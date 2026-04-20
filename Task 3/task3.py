import os
import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestClassifier
from sklearn.model_selection import train_test_split
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix


# data preprocessing
def load_and_preprocess(csv_path):
    df = pd.read_csv(csv_path)

    print(f"Rows: {len(df)}\n")

    # replace inf values with NaN; fill NaN with 0
    df = df.replace([float("inf"), float("-inf")], pd.NA).fillna(0)

    # drop columns we don't need
    df = df.drop(columns=["SchedType", "CoreCount", "CoreId", "IsCoreIdle"])

    print("Action counts overall:")
    print(f" {df['Action'].value_counts()}\n")

    X = df.drop(columns=["Action"])
    y = df["Action"]

    print(f"Number of features: {len(X.columns)}")
    print(f"Feature names: {X.columns.tolist}\n")

    return X, y

# model training
def train_model(X, y):
    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)

    clf = RandomForestClassifier(n_estimators=1000, random_state=42, n_jobs=-1)
    clf.fit(X_train, y_train)

    y_pred = clf.predict(X_test)

    print(f"Accuracy: {accuracy_score(y_test, y_pred):.4f}\n")
    print("Classification Report:")
    print(classification_report(y_test, y_pred))
    print("Confusion Matrix (rows=true, cols=pred):")
    print(confusion_matrix(y_test, y_pred))

    return clf, list(X.columns)

class Process:
    def __init__(self, pid, arrival_time, burst_time):
        self.pid = "P" + str(pid)
        self.arrival_time = float(arrival_time)
        self.burst_time = float(burst_time)
        self.remaining_time = float(burst_time)
        self.completed = False

def get_user_input():
    # get number of processes
    while True:
        try:
            n = int(input("How many processes? (min 2): "))
            if n >= 2:
                break
            print("Need at least 2 processes.")
        except ValueError:
            print("Invalid input, try again.")

    # get RR quantum
    while True:
        try:
            quantum = float(input("RR quantum (e.g., 2): "))
            if quantum > 0:
                break
            print("Quantum must be greater than 0.")
        except ValueError:
            print("Invalid input, try again.")

    processes = []
    for i in range(n):
        # get arrival time
        while True:
            try:
                arrival = float(input("P" + str(i) + " arrival time: "))
                if arrival >= 0:
                    break
                print("Arrival time can't be negative.")
            except ValueError:
                print("Invalid input, try again.")

        # get burst time
        while True:
            try:
                burst = float(input("P" + str(i) + " burst time: "))
                if burst > 0:
                    break
                print("Burst time must be greater than 0.")
            except ValueError:
                print("Invalid input, try again.")

        processes.append(Process(i, arrival, burst))

    return processes, quantum

# build feature vector for the model
def compute_features(ready_queue, total_bursts_ran, initial_total_bursts, feature_names):

    row = {}
    for name in feature_names:
        row[name] = 0.0

    # get remaining burst times from the ready queue
    remaining = []
    for p in ready_queue:
        remaining.append(p.remaining_time)
    if len(remaining) == 0:
        remaining = [0.0]

    row["QueueThreadCount"] = float(len(ready_queue))
    row["QueueMinRemainingBursts"] = float(min(remaining))
    row["QueueMaxRemainingBursts"] = float(max(remaining))
    row["QueueMeanRemainingBursts"] = float(np.mean(remaining))
    row["QueueTotalRemainingBursts"] = float(sum(remaining))
    row["ThreadBurstsRan"] = float(total_bursts_ran)
    row["ThreadBurstsRemaining"] = float(initial_total_bursts - total_bursts_ran)

    return pd.DataFrame([row])[feature_names]

# simulator
def run_simulator(model, feature_names):
    processes, quantum = get_user_input()

    n = len(processes)

    initial_total_bursts = 0.0
    for p in processes:
        initial_total_bursts += p.burst_time

    ready_queue = []
    current_process = None
    rr_ticks = 0
    total_bursts_ran = 0
    completed_count = 0
    log_entries = []

    start_time = processes[0].arrival_time
    for p in processes:
        if p.arrival_time < start_time:
            start_time = p.arrival_time
    time = start_time

    max_time = time + initial_total_bursts + n * 10

    while completed_count < n and time <= max_time:

        # add processes that have arrived
        for p in processes:
            if not p.completed and p is not current_process and p not in ready_queue:
                if p.arrival_time <= time:
                    ready_queue.append(p)

        # check if the running process just finished
        if current_process is not None and current_process.remaining_time <= 0:
            current_process.completed = True
            completed_count += 1
            current_process = None
            rr_ticks = 0
            if completed_count >= n:
                break

        # build feature vector from current ready queue state
        features_df = compute_features(ready_queue, total_bursts_ran, initial_total_bursts, feature_names)

        # ask the model to predict what to do next
        action = model.predict(features_df)[0]

        # map NEXT_OTHER to FCFS, and handle NONE with nothing running
        if action == "NEXT_OTHER":
            action = "NEXT_FCFS"
        if action == "NONE" and current_process is None:
            action = "NEXT_FCFS"

        # if nothing is ready and nothing is running, log idle
        if current_process is None and len(ready_queue) == 0:
            log_entries.append({
                "Time": int(time),
                "ReadyQueue": [],
                "Action": action,
                "Chosen": "IDLE",
                "RemainingAfter": None
            })
            time += 1
            continue

        # execute the predicted action
        if action == "NEXT_FCFS":
            # find earliest arrival from ready queue and currently running process
            selected = None
            for p in ready_queue:
                if selected is None or p.arrival_time < selected.arrival_time:
                    selected = p
            if current_process is not None:
                if selected is None or current_process.arrival_time < selected.arrival_time:
                    selected = current_process
            if selected != current_process:
                ready_queue.remove(selected)
                if current_process is not None:
                    ready_queue.append(current_process)
                current_process = selected
                rr_ticks = 0

        elif action == "NEXT_SJF":
            # find shortest remaining burst; on a tie keep the one already in the queue
            selected = None
            for p in ready_queue:
                if selected is None or p.remaining_time < selected.remaining_time:
                    selected = p
            if current_process is not None:
                if selected is None or current_process.remaining_time < selected.remaining_time:
                    selected = current_process
            if selected != current_process:
                ready_queue.remove(selected)
                if current_process is not None:
                    ready_queue.append(current_process)
                current_process = selected
                rr_ticks = 0

        elif action == "NEXT_RR":
            if current_process is None:
                current_process = ready_queue.pop(0)
                rr_ticks = 0
            elif rr_ticks >= quantum and len(ready_queue) > 0:
                ready_queue.append(current_process)
                current_process = ready_queue.pop(0)
                rr_ticks = 0

        # fallback in case we still don't have a process
        if current_process is None:
            if len(ready_queue) > 0:
                current_process = ready_queue[0]
                for p in ready_queue:
                    if p.arrival_time < current_process.arrival_time:
                        current_process = p
                ready_queue.remove(current_process)
                rr_ticks = 0
            else:
                log_entries.append({
                    "Time": int(time),
                    "ReadyQueue": [],
                    "Action": action,
                    "Chosen": "IDLE",
                    "RemainingAfter": None
                })
                time += 1
                continue

        # record who is waiting after the selection
        ready_pids = []
        for p in ready_queue:
            ready_pids.append(p.pid)

        # run one CPU tick
        current_process.remaining_time -= 1
        total_bursts_ran += 1
        rr_ticks += 1

        log_entries.append({
            "Time": int(time),
            "ReadyQueue": ready_pids,
            "Action": action,
            "Chosen": current_process.pid,
            "RemainingAfter": int(current_process.remaining_time)
        })

        time += 1

    print()
    print("=" * 70)
    print("SCHEDULER DECISION LOG")
    print("=" * 70)
    log_df = pd.DataFrame(log_entries)
    log_df["RemainingAfter"] = log_df["RemainingAfter"].astype("Int64")
    print(log_df.to_string())

def main():
    csv_path = os.path.join("Task 3", "Sample.csv")
    if not os.path.exists(csv_path):
        csv_path = "Sample.csv"

    SEPARATOR = "#" * 96
    print(SEPARATOR)

    X, y = load_and_preprocess(csv_path)
    model, feature_names = train_model(X, y)

    print()
    print("=" * 70)
    print("SIMPLE SIMULATION (ML predicts next queue-level action)")
    print("=" * 70)

    run_simulator(model, feature_names)

    print(SEPARATOR)

if __name__ == "__main__":
    main()