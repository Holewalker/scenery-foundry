import signal
import threading


def worker_identity() -> str:
    return "scenery-foundry.geometry-worker/v1"


def main() -> None:
    stopped = threading.Event()

    def stop(_signum: int, _frame: object) -> None:
        stopped.set()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    print(worker_identity(), flush=True)
    stopped.wait()


if __name__ == "__main__":
    main()
