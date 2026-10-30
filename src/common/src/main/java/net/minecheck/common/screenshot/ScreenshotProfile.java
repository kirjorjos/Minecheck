package net.minecheck.common.screenshot;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records the capture profile a run actually used, next to its captures.
 *
 * <p>A baseline is only meaningful together with the settings that produced it: a different window
 * size, GUI scale, encode scale or chat setting is a different picture, and a different OpenGL
 * renderer (a new CI image, a new Mesa build) can legitimately change every pixel. Writing this
 * file next to the captures lets the baseline comparison report <em>"the profile changed"</em>
 * instead of a wall of unexplainable image diffs.
 *
 * <p>The loader adapters write it from the client thread's first successful capture; the Gradle
 * comparison task copies it into the baseline tree (or compares it against it).
 */
public final class ScreenshotProfile {

  private ScreenshotProfile() {}

  public static final String FILE_NAME = "profile.json";

  /**
   * Writes {@code profile.json} into {@code directory}.
   *
   * @param width the frame width the client actually rendered at
   * @param height the frame height the client actually rendered at
   * @param guiScale the effective GUI scale (framebuffer pixels per GUI pixel)
   * @param scale the encode-time downscale that was applied
   * @param chatVisible whether chat was allowed into the capture
   * @param renderer the OpenGL renderer string ({@code GL_RENDERER})
   */
  public static void write(
      Path directory,
      int width,
      int height,
      int guiScale,
      double scale,
      boolean chatVisible,
      String renderer)
      throws IOException {
    Map<String, Object> profile = new LinkedHashMap<>();
    profile.put("width", width);
    profile.put("height", height);
    profile.put("guiScale", guiScale);
    profile.put("scale", scale);
    profile.put("chatVisible", chatVisible);
    profile.put("renderer", renderer == null ? "" : renderer);
    Files.createDirectories(directory);
    Files.writeString(
        directory.resolve(FILE_NAME),
        new GsonBuilder().setPrettyPrinting().create().toJson(profile) + "\n",
        StandardCharsets.UTF_8);
  }
}
