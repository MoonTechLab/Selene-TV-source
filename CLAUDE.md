## Design Context

### Users
Casual home viewers using a TV remote (D-pad navigation only). They want a relaxed, lean-back experience for browsing movies, TV shows, anime, documentaries, and live streams. The interface should feel effortless — content discovery should be smooth and intuitive without requiring technical knowledge.

### Brand Personality
**Premium, sleek, modern** — a high-end media center experience. Selene-TV should feel like a polished, self-hosted alternative to commercial streaming apps, combining the organizational power of Plex/Jellyfin with the visual refinement of Infuse/IINA.

### Aesthetic Direction
- **Visual tone:** Dark, cinematic, content-first. Let artwork and media shine against deep black backgrounds.
- **References:** Plex/Jellyfin (organized, functional media server feel) and Infuse/IINA (minimal, elegant with refined typography).
- **Anti-references:** Avoid looking cheap, cluttered, or overly colorful. No busy gradients, no aggressive branding, no information overload.
- **Theme:** Dark mode only. Premium glass-morphism with semi-transparent layers (white at 0.08 alpha). Vertical gradient backgrounds (#0a0a0a → #1a1a1a → #0f0f0f → #050505).

### Design Principles
1. **Content is king** — The UI exists to showcase media. Minimize chrome, maximize artwork and metadata visibility. Every element should serve content discovery.
2. **Effortless navigation** — D-pad interactions must feel natural and predictable. Focus states should be immediately obvious with smooth spring animations (1.05x scale, 0.6 damping).
3. **Premium restraint** — Use whitespace, subtle transparency, and refined typography rather than bold colors or decorative elements. The palette is essentially monochrome (white on black) with green (#4CAF50) as the sole accent.
4. **Consistency over cleverness** — Reuse the established component library (TabSwitcher, VideoCard, FilterSelector, RecommendSection). New UI should feel like it belongs, not stand out.
5. **Accessibility by default** — Maintain strong contrast ratios (white text on dark backgrounds), clear focus indicators, and readable font sizes (minimum 11sp) for comfortable 10-foot viewing.

### Technical Constraints
- **Platform:** Android TV (Leanback), minSdk 23, targetSdk 36
- **UI Framework:** Jetpack Compose for TV (androidx.tv.material3)
- **DO NOT** use deprecated `androidx.tv.foundation.lazy.*` components (TvLazyRow, TvLazyColumn). Use standard `LazyRow`, `LazyColumn` from `androidx.compose.foundation`.
- **Design base:** 1080p (1920×1080), density-independent pixels scale to 4K automatically.
- **Input:** D-pad remote only — no touch, no mouse. All interactions must work with directional navigation.
