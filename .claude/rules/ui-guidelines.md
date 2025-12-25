# UI Guidelines

## User Interface

- Full Jetpack Compose with Material 3.
- Custom theming system (`ButlerTheme`, `ButlerColors`).
- Edge-to-edge display support.
- Use icons out of the `androidx.compose.material.icons.twotone` package where possible.
- When creating compose previews, use the `@Preview2` annotation, and wrap the UI element in a `PreviewWrapper`.

## Localization

- All user-facing texts need to be extracted to a `strings.xml` resources file to be localizable.
- Composables should access strings by `stringResource(id = R.string.my_string)`.
- Backend classes (those in the `core`) packages and other non-composables should use `CAString` to provide localized strings.
    - `R.string.xxx.toCaString()`
    - `R.string.xxx.toCaString("Argument")`
    - `caString { getString(R.plurals.xxx, count, count) }`
- Localized strings with multiple arguments should use ordered placeholders (i.e. `%1$s is %2$d`).
- Use ellipsis characters (`…`) instead of 3 manual dots (`...`).
- Use the `strings.xml` file that belongs to respective feature module.
- General texts that are used through-out multiple modules should be placed in the `strings.xml` file of the `app-common` module.
- Before creating a new entry, check if `strings.xml` file in the `app-common` module already contains a general version.
- String IDs should be prefixed with their respective module name. Re-used strings should be prefixed with `general` or `common`.
- Where possible string IDs should not contain implementation details.
    - Postfix with `_action` instead of prefixing with `button_`.
    - Instead of `module_screen_button_open` it should be `module_screen_open_action`

## MVVM with Custom ViewModel Hierarchy

- `ViewModel1` → `ViewModel2` → `ViewModel3` → `ViewModel4`.
- `ViewModel4` adds navigation capabilities.
- Uses Hilt for assisted injection.