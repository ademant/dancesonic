#pragma once

#include <cstdint>
#include <vector>

#include "signalsmith-stretch/signalsmith-stretch.h"

namespace dancesonic {

/**
 * Streaming, pitch-preserving time-stretch of interleaved 16-bit PCM, built on Signalsmith Stretch.
 *
 * The output follows a time map W from input frames to output frames, both counted from the start of the stream.
 * Without a map, W(x) = x / speed. A map is a list of breakpoints (input frame, output frame) with W linear between
 * them; it lets attacks play at close to the original speed while the time between them stretches more. Past the
 * last breakpoint W continues at 1 / speed. The stretcher's pre-roll is dropped at the start and its tail flushed
 * at the end, so output frame W(x) is the input at frame x, and a stream of N frames gives round(W(N)) frames.
 *
 * Not thread-safe; Media3 calls an audio processor from one thread.
 */
class StreamStretcher {
public:
    StreamStretcher(int channels, int sampleRate);

    /**
     * Sets how the next stream is stretched. blockSamples and intervalSamples set the analysis block and hop; 0 keeps
     * Signalsmith's default preset. The breakpoints (count of them, input frames rising from 0, output frames never
     * falling from 0) set the time map; count 0, or a malformed map, is uniform.
     */
    void setPlan(int blockSamples, int intervalSamples, const int64_t *breakIn, const double *breakOut,
                 int breakCount);

    /** Starts a new stream with the plan last set, dropping all buffered input and output. */
    void start(float speed, float pitch);

    /** Sets the plan and starts: setPlan() then start(). */
    void start(float speed, float pitch, int blockSamples, int intervalSamples, const int64_t *breakIn,
               const double *breakOut, int breakCount) {
        setPlan(blockSamples, intervalSamples, breakIn, breakOut, breakCount);
        start(speed, pitch);
    }

    /** Adds input. Processes as much as it can at once. */
    void queueInput(const int16_t *interleaved, int frames);

    /** Marks the end of the input. All remaining output becomes available. */
    void queueEndOfStream();

    /** Output frames ready to read. */
    int availableFrames() const { return int(output.size() / channels) - outputReadFrame; }

    /** Copies up to maxFrames output frames. Returns how many were copied. */
    int readOutput(int16_t *interleaved, int maxFrames);

    /** True once the end of stream was queued and all output has been read. */
    bool isEnded() const { return inputEnded && availableFrames() == 0; }

    int channelCount() const { return channels; }

    /** The analysis block in frames, as configured by the last start(). */
    int blockSamples() const { return stretch.blockSamples(); }

private:
    void configure(int blockSamples, int intervalSamples);
    void processPending();
    void process(int frames, bool silence);
    void appendOutput(int frames);
    void seedIfReady();
    /** W(x): output frames for input frame x. */
    double mapped(int64_t inputFrame) const;
    /** How many frames from x the next breakpoint is, or a large number past the last. */
    int64_t framesToNextBreak(int64_t inputFrame) const;

    const int channels;
    const int sampleRate;
    signalsmith::stretch::SignalsmithStretch<float> stretch;
    int configuredBlock = -1;
    int configuredInterval = -1;
    int planBlock = 0;
    int planInterval = 0;
    float speed = 1.0f;
    std::vector<int64_t> breakIn;
    std::vector<double> breakOut;

    // Input not yet given to the stretcher, one vector per channel.
    std::vector<std::vector<float>> pending;
    // Scratch output from one process() call, one vector per channel.
    std::vector<std::vector<float>> scratch;
    // Interleaved output not yet read; outputReadFrame frames of it were read already.
    std::vector<int16_t> output;
    int outputReadFrame = 0;

    bool seeded = false;
    bool inputEnded = false;
    int64_t inputFramesTotal = 0;     // every real input frame since start()
    int64_t processedFrames = 0;      // input frames (and padding) given to process() after seeding
    int64_t requestedFrames = 0;      // output frames asked of process() after seeding
    int64_t emittedFrames = 0;        // output frames appended after the pre-roll was dropped
    int64_t preRollRemaining = 0;     // stretcher pre-roll still to drop
};

}  // namespace dancesonic
