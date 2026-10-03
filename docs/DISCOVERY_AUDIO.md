# Offline discovery sounds

The oropendola notification uses a real Montezuma oropendola recorded by Félix Blume near Paso de Telaya, Veracruz, Mexico. The source is [Freesound sound 512109](https://freesound.org/people/felix.blume/sounds/512109/), published under [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/). The bundled excerpt uses the public high-quality MP3 preview, rather than the original WAV that requires a Freesound login.

The 1.2-second excerpt at 6.15 through 7.35 seconds keeps the end of the bird's bubbling phrase. Its original pitch and timing are preserved. A 250 Hz high-pass removes low rumble and a 3500 Hz low-pass softens higher harmonics. The result is converted to mono 48 kHz signed 16-bit little-endian PCM, normalized to 90% peak level, and given 15 ms and 35 ms cosine fades to avoid clicks at the endpoints. No synthesized tone or water recording is mixed in.

The sound chooser labels this recording **Oropendola bloop**. Its saved setting remains `OROPENDOLA` and its asset path remains `sounds/oropendola.pcm`, so existing selections use the replacement without a preference migration. The playback engine and tugboat clip are unchanged. Both clips remain bundled and play offline. Source links in the credits open only when tapped.

## Regeneration

Install FFmpeg and Python 3 on the development machine. Download the licensed source outside the repository, then run:

```sh
curl --fail --location 'https://cdn.freesound.org/previews/512/512109_1661766-hq.mp3' \
  --output /tmp/oropendola-felix-blume.mp3
python3 tools/update-oropendola-audio.py /tmp/oropendola-felix-blume.mp3
```

The script checks the source SHA-256 before replacing the bundled PCM. It also creates `build/audio-previews/oropendola-bloop.wav` for listening outside the app. Use `--output` and `--preview` to generate comparison files without replacing the asset. FFmpeg decoder/filter versions can affect sample rounding; inspect any changed output before updating its documented checksum. Source and PCM checksums, credits and processing details are recorded in [Discovery-recordings.txt](../app/src/main/assets/licenses/Discovery-recordings.txt). Commit only the bundled PCM, source recipe and documentation; downloaded sources and WAV previews stay outside Git.

The existing bundled-recordings test checks duration, peak level and zero-valued endpoints in both variants. Playback tests cover full clips, mute cancellation and fades. These checks do not establish how the call sounds through a phone speaker. After installing, open Settings, select **Oropendola bloop**, and tap its preview with media volume raised.

## Verification, 2026-10-03

Both variants passed all 139 automated tests, lint and APK builds. Lint reports no errors and seven existing warnings per variant. Both runtime audits passed for 67 Gradle-resolved artifacts and all 12,137 dependency classes. The replacement PCM and updated credits were verified byte-for-byte in both APKs. Regenerating the PCM from the retained source produced identical bytes. Both signatures verify with the existing field-test certificate; no signing configuration changed. The call has not been verified through a real Android speaker in this session.

The rebuilt release APK SHA-256 is `bcd6b9b0673ca25446db5bbb1e4b7a0722302df94f9f5c72a2ecfe897e74124a`. The debug APK SHA-256 is `d7339a8c7768ea019c6d9be971d79c464e9e03d58de043cf6dd363b9b6527487`.
