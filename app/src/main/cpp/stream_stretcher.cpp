#include "stream_stretcher.h"

#include <algorithm>
#include <cmath>

namespace dancesonic {

namespace {
// A fixed seed keeps the output reproducible, which the tests rely on.
constexpr long kSeed = 0x5eed;
// Upper bound for one process() call, so the scratch buffers stay small.
constexpr int kMaxChunkFrames = 4096;

struct ChannelPointers {
    std::vector<std::vector<float>> &buffers;
    int offset;
    float *operator[](int c) { return buffers[c].data() + offset; }
};

// Above this level (-1 dBFS) peaks are rounded off rather than hard-clipped. Spectral stretching can push the
// peaks of loudly mastered tracks past full scale, and hard clipping would click.
constexpr float kKnee = 0.891f;

float softLimit(float sample) {
    float level = std::fabs(sample);
    if (level <= kKnee) return sample;
    float limited = kKnee + (1.0f - kKnee) * std::tanh((level - kKnee) / (1.0f - kKnee));
    return std::copysign(limited, sample);
}

int16_t toPcm16(float sample) {
    float scaled = std::round(softLimit(sample) * 32768.0f);
    return int16_t(std::clamp(scaled, -32768.0f, 32767.0f));
}
}  // namespace

StreamStretcher::StreamStretcher(int channels, int sampleRate)
    : channels(channels), stretch(kSeed), pending(channels), scratch(channels) {
    stretch.presetDefault(channels, float(sampleRate));
}

void StreamStretcher::start(float newSpeed, float pitch) {
    speed = newSpeed;
    stretch.reset();
    stretch.setTransposeFactor(pitch);
    for (auto &channel : pending) channel.clear();
    output.clear();
    outputReadFrame = 0;
    seeded = false;
    inputEnded = false;
    inputFramesTotal = 0;
    processedFrames = 0;
    requestedFrames = 0;
    emittedFrames = 0;
    preRollRemaining = stretch.outputLatency();
}

void StreamStretcher::queueInput(const int16_t *interleaved, int frames) {
    if (inputEnded || frames <= 0) return;
    for (int c = 0; c < channels; ++c) {
        auto &channel = pending[c];
        size_t start = channel.size();
        channel.resize(start + frames);
        for (int i = 0; i < frames; ++i) {
            channel[start + i] = interleaved[i * channels + c] / 32768.0f;
        }
    }
    inputFramesTotal += frames;
    seedIfReady();
    if (seeded) {
        while (!pending[0].empty()) {
            process(std::min<int>(int(pending[0].size()), kMaxChunkFrames), false);
        }
    }
}

void StreamStretcher::queueEndOfStream() {
    if (inputEnded) return;
    if (!seeded) {
        // A very short stream: pad it so the stretcher can start.
        int missing = stretch.inputLatency() - int(pending[0].size());
        for (auto &channel : pending) channel.insert(channel.end(), std::max(missing, 0), 0.0f);
        seedIfReady();
    }
    while (!pending[0].empty()) {
        process(std::min<int>(int(pending[0].size()), kMaxChunkFrames), false);
    }
    // Move the processing time to the end of the input, then read what the stretcher still holds.
    int tail = stretch.inputLatency();
    while (tail > 0) {
        int chunk = std::min(tail, kMaxChunkFrames);
        process(chunk, true);
        tail -= chunk;
    }
    int flushFrames = stretch.outputLatency();
    for (auto &channel : scratch) channel.assign(flushFrames, 0.0f);
    stretch.flush(ChannelPointers{scratch, 0}, flushFrames);
    appendOutput(flushFrames);

    // Make the length exact: round(input / speed) frames in all.
    int64_t target = std::llround(double(inputFramesTotal) / speed);
    int64_t excess = emittedFrames - target;
    if (excess > 0) {
        output.resize(output.size() - size_t(excess) * channels);
    } else if (excess < 0) {
        output.insert(output.end(), size_t(-excess) * channels, 0);
    }
    emittedFrames = target;
    inputEnded = true;
}

int StreamStretcher::readOutput(int16_t *interleaved, int maxFrames) {
    int frames = std::min(maxFrames, availableFrames());
    if (frames <= 0) return 0;
    std::copy_n(output.begin() + size_t(outputReadFrame) * channels, size_t(frames) * channels, interleaved);
    outputReadFrame += frames;
    if (availableFrames() == 0) {
        output.clear();
        outputReadFrame = 0;
    }
    return frames;
}

void StreamStretcher::seedIfReady() {
    int latency = stretch.inputLatency();
    if (seeded || int(pending[0].size()) < latency) return;
    // Hand over the first inputLatency() frames so the processing time starts at the first input frame.
    stretch.seek(ChannelPointers{pending, 0}, latency, speed);
    for (auto &channel : pending) channel.erase(channel.begin(), channel.begin() + latency);
    seeded = true;
}

void StreamStretcher::process(int frames, bool silence) {
    processedFrames += frames;
    auto target = int64_t(std::floor(double(processedFrames) / speed));
    int outFrames = int(target - requestedFrames);
    requestedFrames = target;

    for (auto &channel : scratch) channel.assign(outFrames, 0.0f);
    if (silence) {
        std::vector<std::vector<float>> zeros(channels, std::vector<float>(frames, 0.0f));
        stretch.process(ChannelPointers{zeros, 0}, frames, ChannelPointers{scratch, 0}, outFrames);
    } else {
        stretch.process(ChannelPointers{pending, 0}, frames, ChannelPointers{scratch, 0}, outFrames);
        for (auto &channel : pending) channel.erase(channel.begin(), channel.begin() + frames);
    }
    appendOutput(outFrames);
}

void StreamStretcher::appendOutput(int frames) {
    int skip = int(std::min<int64_t>(preRollRemaining, frames));
    preRollRemaining -= skip;
    int keep = frames - skip;
    if (keep <= 0) return;
    size_t start = output.size();
    output.resize(start + size_t(keep) * channels);
    for (int i = 0; i < keep; ++i) {
        for (int c = 0; c < channels; ++c) {
            output[start + size_t(i) * channels + c] = toPcm16(scratch[c][skip + i]);
        }
    }
    emittedFrames += keep;
}

}  // namespace dancesonic
