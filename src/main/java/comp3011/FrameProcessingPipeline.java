package comp3011;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.bytedeco.javacv.Frame;

public class FrameProcessingPipeline {

    private final List<FrameProcessor> processors;
    private final List<BlockingQueue<FrameJob>> queues;
    private final List<Thread> workers;

    private volatile boolean running;

    public FrameProcessingPipeline(
            List<FrameProcessor> processors) {

        this.processors =
                new ArrayList<>(processors);

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

        for (FrameProcessor processor :
                processors) {

            processor.initialise(
                    videoInfo
            );
        }

        queues.clear();
        workers.clear();

        for (int i = 0;
                i <= processors.size();
                i++) {

            queues.add(
                    new LinkedBlockingQueue<>()
            );
        }

        running = true;

        for (int i = 0;
                i < processors.size();
                i++) {

            int stageIndex = i;

            Thread worker =
                    new Thread(
                            () -> runStage(
                                    stageIndex
                            )
                    );

            worker.setName(
                    "FrameProcessor-"
                    + stageIndex
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

        queues.get(0).put(
                new FrameJob(
                        frame,
                        info
                )
        );
    }

    public FrameJob takeProcessedFrame()
            throws InterruptedException {

        if (!running) {
            throw new IllegalStateException(
                    "Pipeline is not running"
            );
        }

        return queues
                .get(queues.size() - 1)
                .take();
    }
    
    public FrameJob pollProcessedFrame() {

        if (!running) {
            return null;
        }

        return queues
                .get(queues.size() - 1)
                .poll();
    }

    public void stop() {

        running = false;

        for (Thread worker :
                workers) {

            worker.interrupt();
        }

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
            int stageIndex) {

        FrameProcessor processor =
                processors.get(stageIndex);

        BlockingQueue<FrameJob> inputQueue =
                queues.get(stageIndex);

        BlockingQueue<FrameJob> outputQueue =
                queues.get(stageIndex + 1);

        while (running) {

            try {

                FrameJob job =
                        inputQueue.take();

                if (job.getError() == null) {

                    try {

                        processor.process(
                                job.getFrame(),
                                job.getInfo()
                        );

                    } catch (Exception e) {

                        job.setError(e);
                    }
                }

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