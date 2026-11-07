# Alibaba formatter source

`eclipse-codestyle.xml` comes from Alibaba's official P3C repository:

- [Original file](https://github.com/alibaba/p3c/blob/6c59c8c36ecd8722c712d5685b8c3822c1c8b030/p3c-formatter/eclipse-codestyle.xml)
- Upstream commit: `6c59c8c36ecd8722c712d5685b8c3822c1c8b030`
- Upstream Git blob: `0faa9575c32454bda4d7cc45627ae2e45fb088f9`
- License: Apache 2.0; upstream license text is included in `LICENSE`.
- Retrieved: 2026-11-07.

Project modifications include a space after type casts to agree with the local
Checkstyle whitespace rule, plus settings marked at the end of the XML profile: modern Eclipse
JDT settings expand method bodies and code blocks instead of retaining compact
nonempty bodies. The upstream four-space indentation, 120-column wrapping,
continuation indentation, braces, and method separators remain in effect.

Spotless 3.11.0 runs this profile with the pinned Eclipse JDT 4.26 engine, which
supports the project's Java 17 syntax. These are formatting rules, not the entire
Alibaba Java development handbook. `../checkstyle.xml` adds the executable style
checks; the project's standards document defines their scope and review rules.

IntelliJ IDEA can import the same XML as an Eclipse XML Profile. Importing is a
convenience; Maven Spotless is the authoritative formatting result across IDEs.
Do not maintain an independently edited IDEA profile that can drift from this one.
