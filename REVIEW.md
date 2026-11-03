# Review priorities

- Failure here means: a Dev Service silently reuses or clobbers the wrong containers or host ports, or exposes the stack beyond 127.0.0.1 (random ports included: Elasticsearch runs without security).
- Skip: generated collector configs.
- Cap nits at 3 per review.
- Always needs a human: pom.xml, port and network binding code, image versions.

