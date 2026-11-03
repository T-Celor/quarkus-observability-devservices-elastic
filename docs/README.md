# Documentation

This module contains Antora content for publication on docs.quarkiverse.io.

## Layout

- `antora.yml`: component descriptor
- `modules/ROOT/nav.adoc`: navigation
- `modules/ROOT/pages/index.adoc`: landing page

## Authoring checklist

- Keep behavior descriptions aligned with `deployment` module implementation.
- Keep configuration keys and defaults aligned with `runtime` config mapping.
- Keep Compose/Testcontainers mode semantics aligned with Quarkus Dev Services conventions.

## Local preview (example)

This repository contains only the component content; render it with your regular Antora playbook.

```powershell
Set-Location "E:\reproducer\quartz-obs\extensions\quarkus-observability-devservices-elastic"
mvn -pl docs test
```


