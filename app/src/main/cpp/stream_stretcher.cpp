#include "stream_stretcher.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace dancesonic {

namespace {
// A fixed seed keeps the output reproducible, which the tests rely on.
constexpr long kSeed = 0x5eed;
// Upper bound for one process() call, so the scratch buffers stay small.
constexpr int kMaxChunkFrames = 4096;
// Signalsmith's default preset: 120 ms blocks, 30 ms hop.
constexpr double kDefaultBlockSeconds = 0.12;
constexpr double kDefaultIntervalSeconds = 0.03;

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
    : channels(channels), sampleRate(sampleRate), stretch(kSeed), pending(channels), scratch(channels) {
    configure(0, 0);
}

void StreamStretcher::configure(int blockSamples, int intervalSamples) {
    if (blockSamples <= 0 || intervalSamples <= 0 || intervalSamples > blockSamples) {
        blockSamples = int(sampleRate * kDefaultBlockSeconds);
        intervalSamples = int(sampleRate * kDefaultIntervalSeconds);
    }
    // configure() allocates, so only call it when the size changes.
    if (blockSamples == configuredBlock && intervalSamples == configuredInterval) return;
    stretch.configure(channels, blockSamples, intervalSamples);
    configuredBlock = blockSamples;
    configuredInterval = intervalSamples;
}

void StreamStretcher::setPlan(int blockSamples, int intervalSamples, const int64_t *newBreakIn,
                              const double *newBreakOut, int breakCount) {
    planBlock = blockSamples;
    planInterval = intervalSamples;
    breakIn.clear();
    breakOut.clear();
    // Keep only a well-formed map: starting at (0, 0), input strictly rising, output never falling.
    if (breakCount > 0 && newBreakIn != nullptr && newBreakOut != nullptr && newBreakIn[0] == 0 &&
        newBreakOut[0] == 0.0) {
        bool valid = true;
        for (int i = 1; i < breakCount && valid; ++i) {
            valid = newBreakIn[i] > newBreakIn[i - 1] && newBreakOut[i] >= newBreakOut[i - 1];
        }
        if (valid) {
            breakIn.assign(newBreakIn, newBreakIn + breakCount);
            breakOut.assign(newBreakOut, newBreakOut + breakCount);
        }
    }
}

void StreamStretcher::start(float newSpeed, float pitch) {
    configure(planBlock, planInterval);
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

double StreamStretcher::mapped(int64_t x) const {
    if (breakIn.empty()) return double(x) / speed;
    if (x >= breakIn.back()) return breakOut.back() + double(x - breakIn.back()) / speed;
    // First breakpoint above x; the one before it is at or below x, since breakIn[0] is 0.
    auto upper = std::upper_bound(breakIn.begin(), breakIn.end(), x);
    size_t i = size_t(upper - breakIn.begin());
    double fraction = double(x - breakIn[i - 1]) / double(breakIn[i] - breakIn[i - 1]);
    return breakOut[i - 1] + fraction * (breakOut[i] - breakOut[i - 1]);
}

int64_t StreamStretcher::framesToNextBreak(int64_t x) const {
    auto upper = std::upper_bound(breakIn.begin(), breakIn.end(), x);
    if (upper == breakIn.end()) return std::numeric_limits<int64_t>::max();
    return *upper - x;
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
    if (seeded) processPending();
}

void StreamStretcher::processPending() {
    while (!pending[0].empty()) {
        // Split at breakpoints, so each call has one stretch ratio and the map is followed exactly.
        int64_t chunk = std::min<int64_t>({int64_t(pending[0].size()), kMaxChunkFrames,
                                           framesToNextBreak(processedFrames)});
        process(int(chunk), false);
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
    processPending();
    // Move the processing time to the end of the input, then read what the stretcher still holds.
    int tail = stretch.inputLatency();
    while (tail > 0) {
        int chunk = int(std::min<int64_t>({int64_t(tail), kMaxChunkFrames, framesToNextBreak(processedFrames)}));
        process(chunk, true);
        tail -= chunk;
    }
    int flushFrames = stretch.outputLatency();
    for (auto &channel : scratch) channel.assign(flushFrames, 0.0f);
    stretch.flush(ChannelPointers{scratch, 0}, flushFrames);
    appendOutput(flushFrames);

    // Make the length exact: round(W(input)) frames in all.
    int64_t target = std::llround(mapped(inputFramesTotal));
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
    auto target = int64_t(std::floor(mapped(processedFrames)));
    int outFrames = int(std::max<int64_t>(target - requestedFrames, 0));
    requestedFrames += outFrames;

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
