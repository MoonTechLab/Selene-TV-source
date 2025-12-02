# Tech Stack & Development Rules
This project is an **Android TV Application**.

## Core Technologies
- **Language:** Kotlin
- **UI Framework:** Jetpack Compose for TV (`androidx.tv.material3`)
- **Image Loading:** Coil
- **Video Player:** Media3 (ExoPlayer)
- **Navigation:** Jetpack Navigation Compose

## 🚨 Critical Constraints (MUST FOLLOW)

### 1. TV-Specific Libraries & Components
- **✅ ALLOWED:** `androidx.tv.material3.*` (e.g., `Surface`, `Carousel`, `Text`).
- **❌ BANNED (Deprecated):** `androidx.tv.foundation.lazy.*`
    - **DO NOT USE:** `TvLazyRow`, `TvLazyColumn`, `TvLazyGrid`.
    - **DO NOT USE:** `pivotOffsets` parameter.
    - **DO NOT USE:** `rememberTvLazyListState`.

### 2. Lists & Focus Alignment (UPDATED)
- **Use Standard Lazy Lists:** ALWAYS use standard `LazyRow`, `LazyColumn`, `LazyVerticalGrid` from `androidx.compose.foundation`.
- **Focus Alignment (Pivot):** - The `pivotOffsets` parameter no longer exists.
    - To control scroll position of focused items (e.g., keep focused item in center or at 30% offset), use `BringIntoViewSpec`.
    - **Code Pattern:**
      ```kotlin
      CompositionLocalProvider(
          LocalBringIntoViewSpec provides object : BringIntoViewSpec {
              // Custom logic to align item to specific screen percentage
              override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                  // Example: Center the item
                  return offset + (size / 2f) - (containerSize / 2f)
              }
          }
      ) {
          LazyRow(...)
      }
      ```

### 3. Focus Management
- **Input Method:** D-Pad Remote only.
- **Visuals:** Use `androidx.tv.material3.Surface` (NOT `Box` + `clickable`) to handle focus highlight (scale/border).
- **Imports:** Always import `androidx.compose.foundation.lazy.LazyRow` (NOT `androidx.tv...`).