# Tech Stack & Development Rules
This project is an **Android TV Application**.

## Core Technologies
- **Language:** Kotlin
- **UI Framework:** Jetpack Compose for TV (`androidx.tv.material3`)
- **Image Loading:** Coil
- **Video Player:** Media3 (ExoPlayer)
- **Navigation:** Jetpack Navigation Compose

## 🚨 Critical Constraints (MUST FOLLOW)

### 1. TV-Specific Libraries Only
- **DO NOT USE:** `androidx.compose.material3.*` (Mobile Material).
- **ALWAYS USE:** `androidx.tv.material3.*` (TV Material).
    - Use `androidx.tv.material3.Surface` for card containers.
    - Use `androidx.tv.material3.Text` for typography.
    - Use `androidx.tv.material3.Carousel` for banners.

### 2. Focus Management (The most important part)
- **Input Method:** D-Pad Remote (Up/Down/Left/Right). **NO TOUCH.**
- **Visual Cues:** Every interactive element MUST have a visual change when focused.
- **Implementation:**
    - Use `Surface(onClick = ..., scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f))` pattern.
    - Avoid `clickable` modifier on standard Boxes; use `Surface` instead.

### 3. Screen Layout
- **Target Resolution:** 1920x1080 (1080p).
- **Units:** Use `dp` for layout sizes, `sp` for text. **NEVER use `px`.**
- **Overscan:** Keep critical UI elements at least `32.dp` or `48.dp` away from screen edges.
- **Lists:** Use `TvLazyRow` / `TvLazyColumn` instead of standard `LazyRow`.

### 4. Code Generation Rules
- **Imports:** Always include full imports in code blocks to prevent "Unresolved Reference".
- **Preview:** When creating UI components, try to include a `@Preview(device = Devices.TV_1080p)` block.