/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Gia Minh Pham - student id: a3145117
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.Image;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Owns the FFmpeg decoder, playback clock, seeking logic, audio scheduling,
 * and frame processing. Frame effects are processed through a multithreaded
 * processing pipeline while playback and display remain controlled by JavaFX.
 * </p>
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1;
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;

    //limit buffered work using the number of processors available on the computer
    private static final int MAX_FRAMES_IN_FLIGHT =
            Math.max(
                    2,
                    Runtime.getRuntime()
                            .availableProcessors()
            );

    //keeps processors in the same order selected from the command line
    private final List<FrameProcessor> frameProcessors;

    //runs frame effects on worker threads
    private final FrameProcessingPipeline processingPipeline;

    private final Queue<PendingAudio> pendingAudio =
            new ArrayDeque<>();

    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;

    // This is the critical wiring that allows the framework (JavaFX) to call
    // into our model logic every time it goes around its event loop. Every GUI
    // system has an event loop so that button clicks, key presses and window
    // resizes can be responded to. Most offer ways of running additional logic
    // in either idle time (when there are no pending UI events to process) or
    // on a regular heart beat, such as with this JavaFX AnimationTimer. Here
    // we just create a little anonymous local subclass and override the handle
    // method to call the method we want run every heart beat.
    private final AnimationTimer playbackTimer =
            new AnimationTimer() {

                @Override
                public void handle(long now) {
                    pumpPlayback(now);
                }
            };

    private File videoFile;
    private FFmpegFrameGrabber grabber;
    private JavaFXFrameConverter converter;
    private AudioPlayer audioPlayer;
    private PreparedFrame preparedFrame;
    private boolean prepareNextFrameQueued;
    private boolean playbackOpen;
    private boolean pauseRequested;
    private boolean audioOutputEnabled;
    private boolean audioAvailable;

    //true after the pipeline has been started for the current playback
    private boolean frameProcessorsInitialised;

    private long currentTimestampUs;
    private long videoDurationUs =
            NO_SEEK_REQUEST;

    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private long firstTimestampUs =
            NO_SEEK_REQUEST;

    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs =
            NO_SEEK_REQUEST;

    //number of frames currently waiting for worker processing
    private int framesInFlight;

    //true when the decoder has no more frames to submit
    private boolean inputFinished;

    public VideoPlayerModel(
            boolean audioEnabled,
            List<FrameProcessor> frameProcessors,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {

        audioOutputEnabled =
                audioEnabled;

        this.videoSizeChangedHandler =
                videoSizeChangedHandler;

        this.frameReadyHandler =
                frameReadyHandler;

        this.statusChangedHandler =
                statusChangedHandler;

        this.playbackStateChangedHandler =
                playbackStateChangedHandler;

        this.audioOutputStateChangedHandler =
                audioOutputStateChangedHandler;

        //copy processors so the model owns the selected order
        this.frameProcessors =
                new ArrayList<>(
                        frameProcessors
                );

        //create one pipeline for the selected processors
        this.processingPipeline =
                new FrameProcessingPipeline(
                        this.frameProcessors
                );
    }

    public void play(
            File file) {

        videoFile = file;

        videoDurationUs =
                NO_SEEK_REQUEST;

        videoFrameDurationUs = 0;

        startPlayback(
                0,
                false,
                NO_SEEK_REQUEST
        );
    }

    public void startOver() {
        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(
                -FIVE_SECONDS_US
        );
    }

    public void forwardFiveSeconds() {
        seekRelative(
                FIVE_SECONDS_US
        );
    }

    public void togglePause() {
        if (!playbackOpen) {

            if (videoFile != null) {

                startPlayback(
                        displayableSeekTimestamp(
                                currentTimestampUs
                        ),
                        false,
                        currentTimestampUs
                );
            }

            return;
        }

        pauseRequested =
                !pauseRequested;

        relativeSeekBaseUs =
                NO_SEEK_REQUEST;

        if (pauseRequested) {

            pauseStartedNs =
                    System.nanoTime();

            flushAudioOutput();

        } else {

            resumePlaybackClock(
                    System.nanoTime()
            );
        }

        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        audioOutputEnabled =
                !audioOutputEnabled;

        pendingAudio.clear();

        flushAudioOutput();

        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();

        currentTimestampUs = 0;
        pauseRequested = false;

        notifyStatusChanged(
                "Stopped"
        );

        notifyFrameReady(null);

        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
    }

    private void seekRelative(
            long offsetUs) {

        if (videoFile == null) {
            return;
        }

        long baseTimestampUs =
                relativeSeekBaseUs
                        != NO_SEEK_REQUEST
                        ? relativeSeekBaseUs
                        : currentTimestampUs;

        seekTo(
                baseTimestampUs
                + offsetUs
        );
    }

    private void seekTo(
            long timestampUs) {

        long logicalTimestampUs =
                clampSeekTimestamp(
                        timestampUs
                );

        long grabTimestampUs =
                displayableSeekTimestamp(
                        logicalTimestampUs
                );

        //restart playback so old queued frames cannot appear after a seek
        startPlayback(
                grabTimestampUs,
                pauseRequested,
                logicalTimestampUs
        );
    }

    private void startPlayback(
            long startTimestampUs,
            boolean initiallyPaused,
            long initialRelativeSeekBaseUs) {

        closePlaybackResources();

        pauseRequested =
                initiallyPaused;

        currentTimestampUs =
                initialRelativeSeekBaseUs
                        != NO_SEEK_REQUEST
                        ? initialRelativeSeekBaseUs
                        : startTimestampUs;

        relativeSeekBaseUs =
                initialRelativeSeekBaseUs;

        notifyStatusChanged(
                videoFile.getName()
        );

        try {

            openPlaybackResources(
                    startTimestampUs
            );

            resetPlaybackClock(
                    currentTimestampUs
            );

            playbackOpen = true;

            playbackTimer.start();

            prepareNextFrame();

            notifyPlaybackStateChanged();

        } catch (Exception e) {

            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(
            long startTimestampUs)
            throws Exception {

        converter =
                new JavaFXFrameConverter();

        grabber =
                new FFmpegFrameGrabber(
                        videoFile
                );

        grabber.setImageMode(
                FrameGrabber.ImageMode.COLOR
        );

        grabber.setSampleMode(
                FrameGrabber.SampleMode.SHORT
        );

        grabber.start();

        notifyVideoSizeChanged(
                grabber.getImageWidth(),
                grabber.getImageHeight()
        );

        frameRate =
                grabber.getFrameRate();

        intFrameRate =
                (int) Math.round(
                        frameRate
                );

        videoFrameDurationUs =
                frameRate > 0
                        ? (int) Math.round(
                                1_000_000.0
                                / frameRate
                        )
                        : 0;

        totalVideoFrames =
                grabber
                        .getLengthInVideoFrames();

        long durationUs =
                grabber.getLengthInTime();

        videoDurationUs =
                durationUs > 0
                        ? durationUs
                        : NO_SEEK_REQUEST;

        audioAvailable =
                grabber.hasAudio();

        audioPlayer =
                new AudioPlayer();

        if (audioAvailable) {

            audioPlayer.open(
                    grabber.getSampleRate(),
                    grabber.getAudioChannels()
            );
        }

        if (startTimestampUs > 0) {

            grabber.setTimestamp(
                    startTimestampUs
            );
        }
    }

    private void resetPlaybackClock(
            long logicalTimestampUs) {

        pendingAudio.clear();

        preparedFrame = null;

        currentTimestampUs =
                logicalTimestampUs;

        relativeSeekBaseUs =
                logicalTimestampUs;

        firstTimestampUs =
                NO_SEEK_REQUEST;

        logicalPlaybackBaseUs =
                logicalTimestampUs;

        playbackStartNs = 0;

        pauseStartedNs =
                pauseRequested
                        ? System.nanoTime()
                        : 0;

        //a new playback or seek starts with no queued frames
        framesInFlight = 0;

        inputFinished = false;
    }

    private void resumePlaybackClock(
            long now) {

        if (pauseStartedNs > 0 &&
                playbackStartNs > 0) {

            playbackStartNs +=
                    now - pauseStartedNs;
        }

        pauseStartedNs = 0;
    }

    private void prepareNextFrame() {
        if (!playbackOpen) {
            return;
        }

        try {

            //first check if a worker has finished a frame
            if (preparedFrame == null) {

                collectProcessedFrame();
            }

            //keep feeding the pipeline while there is free buffer space
            if (!pauseRequested &&
                    !inputFinished &&
                    framesInFlight
                            < MAX_FRAMES_IN_FLIGHT) {

                submitNextFrame();
            }

            //check again because processing may have finished while reading
            if (preparedFrame == null) {

                collectProcessedFrame();
            }

            //finish only after the decoder and pipeline are both empty
            if (inputFinished &&
                    framesInFlight == 0 &&
                    preparedFrame == null) {

                finishPlayback();
            }

        } catch (Exception e) {

            handlePlaybackError(e);
        }
    }

    private void submitNextFrame()
            throws Exception {

        while (playbackOpen &&
                !inputFinished) {

            Frame frame =
                    grabFrame();

            if (frame == null) {

                inputFinished = true;
                return;
            }

            long timestampUs =
                    grabber.getTimestamp();

            if (audioAvailable &&
                    frame.samples != null) {

                queueAudio(
                        timestampUs,
                        frame
                );
            }

            //audio and video frames can arrive separately
            if (frame.image == null) {
                continue;
            }

            //start processor workers when the first video frame is available
            if (!frameProcessorsInitialised) {

                processingPipeline.start(
                        new InfoVideo(
                                mediaName(
                                        videoFile
                                ),
                                totalVideoFrames,
                                frame.imageWidth,
                                frame.imageHeight,
                                frame.imageDepth,
                                frame.imageChannels,
                                frame.imageStride,
                                frameRate,
                                intFrameRate,
                                videoFrameDurationUs,
                                grabber.getPixelFormat()
                        )
                );

                frameProcessorsInitialised =
                        true;
            }

            if (firstTimestampUs ==
                    NO_SEEK_REQUEST) {

                firstTimestampUs =
                        timestampUs;
            }

            int frameNumber =
                    grabber.getFrameNumber();

            InfoFrame info =
                    new InfoFrame(
                            frameNumber,
                            timestampUs
                    );

            //clone because the decoder may reuse the same frame memory
            Frame frameCopy =
                    frame.clone();

            //send the copied frame to the worker pipeline
            processingPipeline.submit(
                    frameCopy,
                    info
            );

            framesInFlight++;

            //only read one video frame each time this method is called
            return;
        }
    }

    private void collectProcessedFrame()
            throws Exception {

        if (!frameProcessorsInitialised ||
                preparedFrame != null) {

            return;
        }

        //poll lets JavaFX continue when workers are still busy
        FrameProcessingPipeline.FrameJob job =
                processingPipeline
                        .pollProcessedFrame();

        if (job == null) {
            return;
        }

        framesInFlight--;

        if (job.getError() != null) {
            throw job.getError();
        }

        //start playback timing when the first processed frame is ready
        if (playbackStartNs == 0) {

            playbackStartNs =
                    System.nanoTime();

            if (pauseRequested) {

                pauseStartedNs =
                        playbackStartNs;
            }
        }

        Frame frame =
                job.getFrame();

        InfoFrame info =
                job.getInfo();

        Image image =
                converter.convert(
                        frame
                );

        long relativeTimestampUs =
                Math.max(
                        0,
                        info.timestampUs()
                        - firstTimestampUs
                );

        long logicalTimestampUs =
                logicalPlaybackBaseUs
                + relativeTimestampUs;

        long targetTimeNs =
                playbackStartNs
                + relativeTimestampUs
                * 1_000L;

        preparedFrame =
                new PreparedFrame(
                        image,
                        info.frameNumber(),
                        info.timestampUs(),
                        logicalTimestampUs,
                        targetTimeNs,
                        System.nanoTime()
                );
    }

    //called by JavaFX regularly while playback is running
    //worker threads process frames while this method handles display and audio
    private void pumpPlayback(
            long now) {

        if (!playbackOpen) {
            return;
        }

        writeDueAudio(now);

        if (preparedFrame != null &&
                preparedFrame
                        .targetTimeNs()
                        <= now) {

            displayPreparedFrame(now);
        }

        //keep feeding and collecting frames without blocking the JavaFX thread
        if (!pauseRequested &&
                !prepareNextFrameQueued) {

            prepareNextFrameQueued =
                    true;

            Platform.runLater(
                    () -> {

                        prepareNextFrameQueued =
                                false;

                        if (!pauseRequested) {

                            prepareNextFrame();
                        }
                    }
            );
        }
    }

    private void displayPreparedFrame(
            long now) {

        PreparedFrame frame =
                preparedFrame;

        preparedFrame = null;

        currentTimestampUs =
                frame.logicalTimestampUs();

        relativeSeekBaseUs =
                NO_SEEK_REQUEST;

        if (intFrameRate > 0 &&
                frame.frameNumber()
                % intFrameRate == 0) {

            long remainingUs =
                    (
                            frame.targetTimeNs()
                            - frame.preparedAtNs()
                    )
                    / 1_000L;

            if (remainingUs < 0) {

                System.out.printf(
                        "Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs
                );

            } else {

                System.out.printf(
                        "Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs
                );
            }
        }

        notifyFrameReady(
                frame.image()
        );
    }

    private void queueAudio(
            long timestampUs,
            Frame frame) {

        if (!audioOutputEnabled ||
                audioPlayer == null) {

            return;
        }

        byte[] samples =
                audioPlayer.copySamples(
                        frame
                );

        if (samples.length > 0) {

            pendingAudio.add(
                    new PendingAudio(
                            timestampUs,
                            samples
                    )
            );
        }
    }

    private void writeDueAudio(
            long now) {

        if (!audioOutputEnabled ||
                audioPlayer == null) {

            pendingAudio.clear();
            return;
        }

        if (pauseRequested ||
                firstTimestampUs
                        == NO_SEEK_REQUEST ||
                playbackStartNs <= 0) {

            return;
        }

        long dueTimestampUs =
                firstTimestampUs
                + (
                        now
                        + AUDIO_LEAD_NS
                        - playbackStartNs
                )
                / 1_000L;

        while (!pendingAudio.isEmpty()) {

            PendingAudio audio =
                    pendingAudio.peek();

            if (audio.timestampUs() >
                    dueTimestampUs) {

                return;
            }

            int written =
                    audioPlayer.write(
                            audio.samples(),
                            audio.offset(),
                            audio.remaining()
                    );

            if (written <= 0) {
                return;
            }

            audio.advance(
                    written
            );

            if (!audio.finished()) {
                return;
            }

            pendingAudio.remove();
        }
    }

    private void finishPlayback() {
        long endTimestampUs =
                videoDurationUs
                        != NO_SEEK_REQUEST
                        ? videoDurationUs
                        : currentTimestampUs;

        closePlaybackResources();

        pauseRequested = true;

        currentTimestampUs =
                endTimestampUs;

        relativeSeekBaseUs =
                endTimestampUs;

        notifyStatusChanged(
                "Playback finished"
        );

        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(
            Exception e) {

        e.printStackTrace();

        closePlaybackResources();

        pauseRequested = false;

        notifyStatusChanged(
                "Error: "
                + e.getMessage()
        );

        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
        playbackTimer.stop();

        playbackOpen = false;

        preparedFrame = null;

        pendingAudio.clear();

        firstTimestampUs =
                NO_SEEK_REQUEST;

        playbackStartNs = 0;

        pauseStartedNs = 0;

        audioAvailable = false;

        frameProcessorsInitialised =
                false;

        //stop and join worker threads before processors are reused
        processingPipeline.stop();

        if (audioPlayer != null) {

            audioPlayer.close();

            audioPlayer = null;
        }

        if (grabber != null) {

            try {

                grabber.stop();

            } catch (Exception e) {

                // The resource is being closed; there is no useful recovery action.
            }

            try {

                grabber.close();

            } catch (Exception e) {

                // The resource is being closed; there is no useful recovery action.
            }

            grabber = null;
        }

        if (converter != null) {

            converter.close();

            converter = null;
        }
    }

    private String mediaName(
            File file) {

        String fileName =
                file.getName();

        int extensionStart =
                fileName.lastIndexOf('.');

        if (extensionStart <= 0) {
            return fileName;
        }

        return fileName.substring(
                0,
                extensionStart
        );
    }

    private long clampSeekTimestamp(
            long timestampUs) {

        long clampedTimestampUs =
                Math.max(
                        0,
                        timestampUs
                );

        long durationUs =
                videoDurationUs;

        if (durationUs !=
                NO_SEEK_REQUEST) {

            clampedTimestampUs =
                    Math.min(
                            clampedTimestampUs,
                            durationUs
                    );
        }

        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(
            long logicalTimestampUs) {

        long durationUs =
                videoDurationUs;

        if (durationUs ==
                NO_SEEK_REQUEST ||
                logicalTimestampUs <
                        durationUs) {

            return logicalTimestampUs;
        }

        int frameDurationUs =
                videoFrameDurationUs;

        if (frameDurationUs <= 0) {

            return Math.max(
                    0,
                    durationUs - 1
            );
        }

        return Math.max(
                0,
                durationUs
                - frameDurationUs
        );
    }

    private Frame grabFrame()
            throws Exception {

        if (audioAvailable) {
            return grabber.grab();
        }

        return grabber.grabImage();
    }

    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }

    private void notifyVideoSizeChanged(
            int width,
            int height) {

        videoSizeChangedHandler.accept(
                width,
                height
        );
    }

    private void notifyFrameReady(
            Image image) {

        frameReadyHandler.accept(
                image
        );
    }

    private void notifyStatusChanged(
            String status) {

        statusChangedHandler.accept(
                status
        );
    }

    private void notifyPlaybackStateChanged() {

        playbackStateChangedHandler.accept(
                playbackOpen,
                pauseRequested
        );
    }

    private void notifyAudioOutputStateChanged() {

        audioOutputStateChangedHandler.accept(
                audioOutputEnabled
        );
    }

    private record PreparedFrame(
            Image image,
            int frameNumber,
            long mediaTimestampUs,
            long logicalTimestampUs,
            long targetTimeNs,
            long preparedAtNs) {
    }

    private static class PendingAudio {

        private final long timestampUs;
        private final byte[] samples;

        private int offset;

        PendingAudio(
                long timestampUs,
                byte[] samples) {

            this.timestampUs =
                    timestampUs;

            this.samples =
                    samples;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        int offset() {
            return offset;
        }

        int remaining() {

            return samples.length
                    - offset;
        }

        void advance(
                int byteCount) {

            offset +=
                    byteCount;
        }

        boolean finished() {

            return offset >=
                    samples.length;
        }
    }
}