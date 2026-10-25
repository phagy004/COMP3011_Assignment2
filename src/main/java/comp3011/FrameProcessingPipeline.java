package comp3011;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.bytedeco.javacv.Frame;

public class FrameProcessingPipeline {

    //stores processors in command line order
    private final List<FrameProcessor> processors;

    //moves frames between worker stages
    private final List<BlockingQueue<FrameJob>> queues;

    //stores the worker threads
    private final List<Thread> workers;

    private volatile boolean running;

    public FrameProcessingPipeline(
            List<FrameProcessor> processors) {

        this.processors =
                new ArrayList<>(
                        processors
                );

        this.queues =
                new ArrayList<>();

        this.workers =
                new ArrayList<>();
    }

    public void start(
            InfoVideo videoInfo)
            throws Exception {

        if (running) {
            return;
        }

        //initialise processors before workers start
        for (FrameProcessor processor :
                processors) {

            processor.initialise(
                    videoInfo
            );
        }

        queues.clear();
        workers.clear();

        //do not create more workers than available CPU cores
        int workerCount =
                Math.min(
                        processors.size(),
                        Math.max(
                                1,
                                Runtime.getRuntime()
                                        .availableProcessors()
                        )
                );

        //no processors means frames can go straight to output
        if (processors.isEmpty()) {

            queues.add(
                    new LinkedBlockingQueue<>()
            );

            running = true;

            return;
        }

        //one queue before each worker and one output queue
        for (int i = 0;
                i <= workerCount;
                i++) {

            queues.add(
                    new LinkedBlockingQueue<>()
            );
        }

        running = true;

        //split processors into groups while keeping their order
        for (int i = 0;
                i < workerCount;
                i++) {

            int workerIndex = i;

            int startIndex =
                    workerIndex
                    * processors.size()
                    / workerCount;

            int endIndex =
                    (workerIndex + 1)
                    * processors.size()
                    / workerCount;

            Thread worker =
                    new Thread(
                            () -> runStage(
                                    workerIndex,
                                    startIndex,
                                    endIndex
                            )
                    );

            worker.setName(
                    "FrameWorker-"
                    + workerIndex
            );

            worker.start();

            workers.add(worker);
        }
    }

    public void submit(
            Frame frame,
            InfoFrame info)
            throws InterruptedException {

        if (!running) {

            throw new IllegalStateException(
                    "Pipeline is not running"
            );
        }

        //put the frame into the first queue
        queues.get(0).put(
                new FrameJob(
                        frame,
                        info
                )
        );
    }

    public FrameJob pollProcessedFrame() {

        if (!running) {
            return null;
        }

        //read output without blocking JavaFX
        return queues
                .get(
                        queues.size() - 1
                )
                .poll();
    }

    public void stop() {

        running = false;

        //wake workers waiting for frames
        for (Thread worker :
                workers) {

            worker.interrupt();
        }

        //wait until all workers finish
        for (Thread worker :
                workers) {

            try {

                worker.join();

            } catch (InterruptedException e) {

                Thread.currentThread()
                        .interrupt();

                break;
            }
        }

        workers.clear();
        queues.clear();
    }

    private void runStage(
            int workerIndex,
            int startIndex,
            int endIndex) {

        BlockingQueue<FrameJob> inputQueue =
                queues.get(
                        workerIndex
                );

        BlockingQueue<FrameJob> outputQueue =
                queues.get(
                        workerIndex + 1
                );

        while (running) {

            try {

                FrameJob job =
                        inputQueue.take();

                //process this worker's group of effects in order
                if (job.getError() == null) {

                    for (int i = startIndex;
                            i < endIndex;
                            i++) {

                        try {

                            processors
                                    .get(i)
                                    .process(
                                            job.getFrame(),
                                            job.getInfo()
                                    );

                        } catch (Exception e) {

                            job.setError(e);

                            break;
                        }
                    }
                }

                //pass the frame to the next worker
                outputQueue.put(job);

            } catch (InterruptedException e) {

                Thread.currentThread()
                        .interrupt();

                break;
            }
        }
    }

    public static class FrameJob {

        private final Frame frame;
        private final InfoFrame info;

        private Exception error;

        public FrameJob(
                Frame frame,
                InfoFrame info) {

            this.frame = frame;
            this.info = info;
        }

        public Frame getFrame() {
            return frame;
        }

        public InfoFrame getInfo() {
            return info;
        }

        public Exception getError() {
            return error;
        }

        public void setError(
                Exception error) {

            this.error = error;
        }
    }
}