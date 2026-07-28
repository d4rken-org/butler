# Coding Standards

- Package by feature, not by layer.
- All user-facing strings are extracted to `values/strings.xml` and localized (details: `localization.md`).
- Prefer adding to existing files unless creating new logical components.
- **Composable organization**:
    - Reusable composables get their own files (e.g., `ButlerIcon.kt`); screen-specific composables stay in the screen file until it exceeds ~200 lines, then extract.
    - **Always add `@Preview2` functions for ALL composables**, including screen-level pages. For screens with Flow/ViewModel dependencies, preview with mock state via `flowOf()`; cover distinct UI states (empty, loading, data, error) where applicable.
    - Place previews below the composable being previewed; name them `ComponentNamePreview()`, marked `private`.
- Write minimalistic and concise code. Match the surrounding code's comment density and idiom — no comments narrating obvious code.
- Prefer flow-based, reactive solutions.
- Style: brackets on any multi-line `if`; in `when`, braces only for multi-statement branches (a composable's trailing lambda needs no wrapper braces); always add trailing commas.
- In `@Composable` functions, `modifier: Modifier = Modifier,` is the first parameter.
