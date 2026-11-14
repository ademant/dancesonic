# Third-party native code

Vendored so the build fetches nothing. These are unmodified copies of the upstream headers; update them by copying the
files from a new tag.

| Directory | Project | Version | Commit | License |
|---|---|---|---|---|
| `signalsmith-stretch/` | [Signalsmith Stretch](https://github.com/Signalsmith-Audio/signalsmith-stretch) | 1.4.0 | `a670068d9aeb64913331d5cc29337b19a457a7df` | MIT |
| `signalsmith-linear/` | [Signalsmith Linear](https://github.com/Signalsmith-Audio/linear) | 0.6.4 | `de55e6a50ffcf6f8f43f649692d94691c7025151` | MIT |

Only the headers the build uses are kept: `signalsmith-stretch.h`, and Linear's `approx.h`, `fft.h`, `linear.h`,
`stft.h` and the `include/signalsmith-linear/` forwarding headers. Linear's `platform/` back ends (Accelerate, IPP,
PFFFT, CMSIS-DSP) are only included under build flags this project does not set.
