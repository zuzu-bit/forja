The two-minutes-silence.m4a fixture contains generated silence, no human recording.

Generate with FFmpeg:

```sh
ffmpeg -f lavfi -i anullsrc=r=44100:cl=mono -t 120 -c:a aac -b:a 64k -movflags +faststart two-minutes-silence.m4a
```
