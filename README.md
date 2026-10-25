## Multithreading

In the previous version, the video player was processing all frames on the JavaFX thread, which could slow down the performance if there were several frame effects applied at once.
Now I have implemented FrameProcessingPipeline, which is responsible for processing frames with multiple threads. Every frame processor works as a stage of the pipeline and has its own thread and a queue for passing frames to the next stage.
Thus, different frames can be processed in different stages simultaneously, while keeping the same command-line order of frame processors.
Frames are cloned before going into the pipeline because the video decoder might use the data of the frame.
The JavaFX thread polls for the frames being processed, which makes the application responsive even though worker threads are doing their job.
The size of the pipeline depends on the number of processors on the machine.

## Performance Testing

The performance of the player was checked by applying several FrameBleeder effects, which require more processing power.
Without multithreading, five bleed effects caused significant lag on my machine.
With the multithreaded pipeline, five bleed effects could work with positive frame headroom during local testing.
The performance might vary depending on the computer and the number of CPU cores.