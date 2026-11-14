#pragma once

#include <cstdint>
#include <vector>

#include "signalsmith-stretch/signalsmith-stretch.h"

namespace dancesonic {

/**
 * Streaming, pitch-preserving time-stretch of interleaved 16-bit PCM, built on Signalsmith Stretch.
 *
 * The output is aligned with the input: output frame k is the input at time k * speed. The stretcher's
 * pre-roll is dropped at the start, and its tail is flushed at the end of the stream, so a stream of
 * N input frames gives round(N / speed) output frames.
 *
 * Not thread-safe; Media3 calls an audio processor from one thread.
 */
class StreamStretcher {
public:
    StreamStretcher(int channels, int sampleRate);

    /** Starts a new stream at this speed and pitch, dropping all buffered input and output. */
    void start(float speed, float pitch);

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

private:
    void process(int frames, bool silence);
    void appendOutput(int frames);
    void seedIfReady();

    const int channels;
    signalsmith::stretch::SignalsmithStretch<float> stretch;
    float speed = 1.0f;

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
