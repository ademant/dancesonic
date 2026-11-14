// Host test for StreamStretcher. Runs without Android:
//   cmake -S app/src/test/cpp -B build/stretch-test && cmake --build build/stretch-test && build/stretch-test/stream_stretcher_test

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "stream_stretcher.h"

namespace {
constexpr int kRate = 44100;
constexpr double kPi = 3.14159265358979323846;
int failures = 0;

void check(bool ok, const char *what) {
    std::printf("%s %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) ++failures;
}

std::vector<int16_t> sine(double hz, int frames, int channels) {
    std::vector<int16_t> out(size_t(frames) * channels);
    for (int i = 0; i < frames; ++i) {
        auto s = int16_t(std::lround(10000.0 * std::sin(2 * kPi * hz * i / kRate)));
        for (int c = 0; c < channels; ++c) out[size_t(i) * channels + c] = s;
    }
    return out;
}

struct TimeMap {
    std::vector<int64_t> in;
    std::vector<double> out;
    int block = 0;
};

/** Feeds the input in uneven blocks, as Media3 does, and reads everything back. */
std::vector<int16_t> run(dancesonic::StreamStretcher &stretcher, const std::vector<int16_t> &input, float speed,
                         const TimeMap &map = {}) {
    int channels = stretcher.channelCount();
    int frames = int(input.size()) / channels;
    stretcher.start(speed, 1.0f, map.block, map.block / 4, map.in.data(), map.out.data(), int(map.in.size()));
    std::vector<int16_t> out;
    std::vector<int16_t> block(size_t(1500) * channels);
    auto drain = [&] {
        int n;
        while ((n = stretcher.readOutput(block.data(), 1500)) > 0) {
            out.insert(out.end(), block.begin(), block.begin() + size_t(n) * channels);
        }
    };
    const int sizes[] = {1, 512, 4096, 1000, 9000, 37};
    int pos = 0;
    for (int i = 0; pos < frames; ++i) {
        int n = std::min(sizes[i % 6], frames - pos);
        stretcher.queueInput(input.data() + size_t(pos) * channels, n);
        pos += n;
        drain();
    }
    stretcher.queueEndOfStream();
    drain();
    return out;
}

/** Frequency from zero crossings of channel 0 over the frames [from, to). */
double frequency(const std::vector<int16_t> &pcm, int channels, int from, int to) {
    int crossings = 0;
    for (int i = from + 1; i < to; ++i) {
        if (pcm[size_t(i - 1) * channels] < 0 && pcm[size_t(i) * channels] >= 0) ++crossings;
    }
    return crossings * double(kRate) / (to - from);
}

/**
 * A map that plays [window] frames around each onset at 1x and makes up for it in the gap before, so each segment
 * between onsets lasts exactly its nominal length. Like StretchPlanner, without its cap.
 */
TimeMap protectOnsets(const std::vector<int> &onsets, int before, int after, float speed, int block) {
    TimeMap map;
    map.block = block;
    map.in.push_back(0);
    map.out.push_back(0);
    double n = 1.0 / speed;
    int64_t segment = 0;
    for (size_t i = 0; i < onsets.size(); ++i) {
        int64_t a = onsets[i] - before, b = onsets[i] + after;
        int64_t end = i + 1 < onsets.size() ? (b + onsets[i + 1] - before) / 2 : b + (b - a);
        double gap = ((end - segment) * n - (b - a)) / double((end - segment) - (b - a));
        double outA = segment * n + (a - segment) * gap;
        map.in.insert(map.in.end(), {a, b, end});
        map.out.insert(map.out.end(), {outA, outA + double(b - a), end * n});
        segment = end;
    }
    return map;
}

double mapped(const TimeMap &map, int64_t x, float speed) {
    if (map.in.empty()) return x / double(speed);
    if (x >= map.in.back()) return map.out.back() + (x - map.in.back()) / double(speed);
    size_t i = 1;
    while (map.in[i] <= x) ++i;
    return map.out[i - 1] + double(x - map.in[i - 1]) / double(map.in[i] - map.in[i - 1]) * (map.out[i] - map.out[i - 1]);
}

double rms(const std::vector<int16_t> &pcm, int from, int to) {
    double energy = 0;
    from = std::max(from, 0);
    to = std::min<int>(to, int(pcm.size()));
    for (int i = from; i < to; ++i) energy += double(pcm[size_t(i)]) * pcm[size_t(i)];
    return to > from ? std::sqrt(energy / (to - from)) : 0.0;
}

/** First frame whose level passes the threshold. */
int onset(const std::vector<int16_t> &pcm, int channels, int threshold) {
    for (size_t i = 0; i < pcm.size(); i += channels) {
        if (std::abs(pcm[i]) > threshold) return int(i / channels);
    }
    return -1;
}
}  // namespace

int main() {
    const int frames = kRate * 3;
    for (int channels : {1, 2}) {
        dancesonic::StreamStretcher stretcher(channels, kRate);
        for (float speed : {0.5f, 0.8f, 1.0f, 1.25f, 1.5f}) {
            auto out = run(stretcher, sine(440.0, frames, channels), speed);
            int outFrames = int(out.size()) / channels;
            char label[96];

            std::snprintf(label, sizeof label, "%dch %.2fx: length is input / speed", channels, speed);
            check(outFrames == std::lround(frames / speed), label);

            double hz = frequency(out, channels, outFrames / 4, outFrames * 3 / 4);
            std::snprintf(label, sizeof label, "%dch %.2fx: pitch stays at 440 Hz (got %.1f)", channels, speed, hz);
            check(std::abs(hz - 440.0) < 5.0, label);
        }
    }

    // A click after one second must come out at one second / speed: no pre-roll delay.
    {
        dancesonic::StreamStretcher stretcher(1, kRate);
        std::vector<int16_t> input(size_t(kRate) * 2, 0);
        for (int i = 0; i < 400; ++i) input[size_t(kRate) + i] = int16_t(20000 * std::sin(2 * kPi * 1000.0 * i / kRate));
        const float speed = 0.8f;
        auto out = run(stretcher, input, speed);
        int at = onset(out, 1, 2000);
        int expected = int(kRate / speed);
        char label[96];
        std::snprintf(label, sizeof label, "click lands at input time / speed (off by %d frames)", at - expected);
        // Spectral processing smears an onset by part of a block, so allow 20 ms.
        check(at >= 0 && std::abs(at - expected) < kRate / 50, label);
    }

    // Peaks past full scale are rounded off below it, not hard-clipped; quiet audio is untouched.
    {
        dancesonic::StreamStretcher stretcher(1, kRate);
        std::vector<int16_t> input(size_t(kRate) * 2);
        for (int i = 0; i < kRate * 2; ++i) {
            // Three tones peaking near full scale, like a loud master: stretching shifts their phases, so the
            // sum overshoots.
            double t = double(i) / kRate;
            double s = std::sin(2 * kPi * 220 * t) + std::sin(2 * kPi * 331 * t + 1) + std::sin(2 * kPi * 497 * t + 2);
            input[size_t(i)] = int16_t(std::lround(s / 3.0 * 32000));
        }
        auto out = run(stretcher, input, 0.7f);
        int clipped = 0;
        for (int16_t s : out) clipped += (s == 32767 || s == -32768);
        check(clipped == 0, "overshoot is soft-limited, never hard-clipped");
    }

    // With a time map: the length is round(W(N)), and a click at an onset lands where the map puts it.
    {
        dancesonic::StreamStretcher stretcher(1, kRate);
        std::vector<int16_t> input(size_t(kRate) * 3, 0);
        const int click = kRate + 12345;
        for (int i = 0; i < 400; ++i) input[size_t(click + i)] = int16_t(20000 * std::sin(2 * kPi * 1000.0 * i / kRate));
        const float speed = 0.6f;
        TimeMap map = protectOnsets({kRate / 2, click, 2 * kRate}, 2646, 900, speed, 0);
        auto out = run(stretcher, input, speed, map);
        char label[96];
        std::snprintf(label, sizeof label, "map: length is round(W(N)) (%zu)", out.size());
        check(int64_t(out.size()) == std::llround(mapped(map, int64_t(input.size()), speed)), label);
        int at = onset(out, 1, 2000);
        int expected = int(mapped(map, click, speed));
        std::snprintf(label, sizeof label, "map: click lands at W(onset) (off by %d frames)", at - expected);
        check(at >= 0 && std::abs(at - expected) < kRate / 50, label);
        // Segment ends are on the nominal timeline.
        check(std::abs(map.out[3] - map.in[3] / double(speed)) < 1e-6, "map: segment end is nominal");
    }

    // Onset-aware stretching keeps attacks sharper: less pre-echo before each pluck than uniform stretching.
    {
        std::vector<int16_t> input(size_t(kRate) * 6);
        std::vector<int> onsets;
        for (int i = 0; i < kRate * 6; ++i) {
            double t = double(i) / kRate;
            double pad = 0.06 * (std::sin(2 * kPi * 196 * t) + std::sin(2 * kPi * 247 * t) + std::sin(2 * kPi * 294 * t));
            input[size_t(i)] = int16_t(std::lround(pad * 32000));
        }
        uint32_t noise = 1;
        for (int ms = 300; ms < 5600; ms += 330 + (ms % 7) * 9) {
            int start = ms * kRate / 1000;
            onsets.push_back(start);
            for (int i = 0; i < kRate / 4 && start + i < kRate * 6; ++i) {
                noise = noise * 1664525u + 1013904223u;
                double t = double(i) / kRate, env = std::exp(-t / 0.035);
                double hit = env * (0.35 * (int32_t(noise) / 2147483648.0) + 0.4 * std::sin(2 * kPi * 523 * t));
                input[size_t(start + i)] = int16_t(std::clamp(input[size_t(start + i)] + hit * 32000, -32768.0, 32767.0));
            }
        }
        const float speed = 0.6f;
        const int block = int(kRate * 0.12);
        TimeMap uniform;
        uniform.block = block;
        TimeMap aware = protectOnsets(onsets, block / 2, block * 17 / 100, speed, block);
        double ratio[2];
        for (int mode = 0; mode < 2; ++mode) {
            dancesonic::StreamStretcher stretcher(1, kRate);
            const TimeMap &map = mode ? aware : uniform;
            auto out = run(stretcher, input, speed, map);
            double pre = 0, post = 0;
            for (int o : onsets) {
                int at = int(mapped(map, o, speed));
                pre += rms(out, at - kRate * 30 / 1000, at - kRate * 5 / 1000);
                post += rms(out, at, at + kRate * 15 / 1000);
            }
            ratio[mode] = pre / post;
        }
        char label[120];
        std::snprintf(label, sizeof label, "onset map: less pre-echo than uniform (%.3f vs %.3f)", ratio[1], ratio[0]);
        check(ratio[1] < ratio[0] * 0.92, label);
    }

    // A map that does not start at (0, 0) is ignored: the stream is stretched uniformly.
    {
        dancesonic::StreamStretcher stretcher(2, kRate);
        TimeMap bad;
        bad.in = {100, 200};
        bad.out = {0, 50};
        auto out = run(stretcher, sine(440.0, 10000, 2), 0.5f, bad);
        check(out.size() / 2 == 20000, "invalid map falls back to uniform");
    }

    // The block size can be chosen per stream.
    {
        dancesonic::StreamStretcher stretcher(1, kRate);
        TimeMap shortBlocks;
        shortBlocks.block = 4000;
        run(stretcher, sine(440.0, 20000, 1), 0.8f, shortBlocks);
        check(stretcher.blockSamples() == 4000, "block size follows the plan");
        run(stretcher, sine(440.0, 20000, 1), 0.8f);
        check(stretcher.blockSamples() == int(kRate * 0.12), "block 0 means the default");
    }

    // Short streams still give the right length.
    {
        dancesonic::StreamStretcher stretcher(2, kRate);
        auto out = run(stretcher, sine(440.0, 300, 2), 0.5f);
        check(out.size() / 2 == 600, "short stream: 300 frames at 0.5x give 600 frames");
        auto empty = run(stretcher, {}, 0.5f);
        check(empty.empty() && stretcher.isEnded(), "empty stream ends with no output");
    }

    std::printf("%s\n", failures == 0 ? "all passed" : "FAILED");
    return failures == 0 ? 0 : 1;
}
