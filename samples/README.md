# Samples

You can replace any of the built-in synthesized drums by adding a WAV file here:

| File | Instrument |
| --- | --- |
| `bd.wav` | kick |
| `sd.wav` | snare |
| `hh.wav` | closed hi-hat |
| `oh.wav` | open hi-hat |
| `cp.wav` | clap |

Files recognized by Java Sound are converted to signed 16-bit mono at 44.1 kHz when the application starts. Mono and stereo PCM WAV files are the safest choice for workshop laptops. Missing files use deterministic synthesized fallbacks.
