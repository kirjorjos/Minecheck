package net.minecheck.platform.api;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * A window that shows the client's frames, for a run whose game window the human cannot see — the
 * normal case when a lifecycle run drives the client on a machine without a display, and the point
 * of the option when it drives one on a display the human is not looking at.
 *
 * <p><b>View only.</b> The window installs <em>no</em> mouse listener: the pointer is never
 * captured, grabbed or hidden, so clicking it does nothing at all. Keys typed into it are not
 * delivered to the game as themselves but to a {@link KeySink} — Minecheck's virtual keyboard — so
 * the window behaves exactly like a {@code press} action and is marked with {@link InjectedInput}
 * by the receiving platform. Nothing the window does can move, aim or interact with the player by
 * another route.
 *
 * <p><b>Never fatal.</b> A window is a convenience, so every failure — a headless JVM, no display,
 * no toolkit — is logged and turned into a {@code null} from {@link #open}: the run continues
 * without it. {@link #open} is the only entry point and never throws.
 *
 * <p><b>Threading.</b> Frames arrive on the game (render) thread through {@link #offerFrame} and
 * are copied only on the AWT event thread, driven by a timer. Offering a frame therefore never
 * blocks, never touches AWT state, and drops nothing that matters: the newest frame wins, and a
 * frame the human never sees was one 10th of a second behind the next one anyway.
 */
public final class ViewWindow {

  private static final Logger LOG = Logger.getLogger("Minecheck");

  /** How often the event thread looks for a new frame (ms). Cheap: a no-op when nothing arrived. */
  private static final int REPAINT_PERIOD_MS = 50;

  /**
   * Where the window's keys go: Minecheck's virtual keyboard. {@code glfwKey} is a GLFW key code
   * and {@code down} says press (true) or release (false).
   */
  public interface KeySink {
    void key(int glfwKey, boolean down);
  }

  /** One frame of pixels, ARGB, top row first, {@code width * height} long. */
  private record Frame(int[] pixels, int width, int height) {}

  private final AtomicReference<Frame> pending = new AtomicReference<>();

  private JFrame frame;
  private FramePanel panel;
  private Timer timer;

  /** The frame the panel is currently showing, so identical offers do not repaint. */
  private Frame painted;

  private BufferedImage image;

  private ViewWindow() {}

  /**
   * Opens the window, or returns {@code null} when this JVM cannot show one (headless, no display,
   * no AWT toolkit) — in which case the reason has been logged.
   *
   * @param title window title
   * @param sink where keys typed into the window are dispatched
   */
  public static ViewWindow open(String title, KeySink sink) {
    if (GraphicsEnvironment.isHeadless()) {
      LOG.warning(
          "Minecheck: viewWindow is on, but this JVM is headless — running without a view window");
      return null;
    }
    ViewWindow window = new ViewWindow();
    try {
      SwingUtilities.invokeAndWait(() -> window.create(title, sink));
    } catch (Throwable t) {
      LOG.log(Level.WARNING, "Minecheck: could not open the view window — running without it", t);
      return null;
    }
    if (window.frame == null) {
      return null;
    }
    LOG.info("Minecheck: view window open (view only — no mouse input, keys go through press())");
    return window;
  }

  /**
   * Offers the client's latest frame; the newest one wins. Called on the game thread once per pump
   * — never blocks and never throws.
   */
  public void offerFrame(int[] argb, int width, int height) {
    if (argb == null || width <= 0 || height <= 0 || argb.length < width * height) {
      return;
    }
    pending.set(new Frame(argb, width, height));
  }

  /** True while the window is on screen: a closed or disposed window stops the frame pumping. */
  public boolean visible() {
    JFrame f = frame;
    return f != null && f.isDisplayable() && f.isVisible();
  }

  /** Closes the window and stops its repaint timer. Safe from any thread, and from null. */
  public void close() {
    JFrame f = frame;
    Timer t = timer;
    frame = null;
    timer = null;
    SwingUtilities.invokeLater(
        () -> {
          if (t != null) {
            t.stop();
          }
          if (f != null) {
            f.dispose();
          }
        });
  }

  // ---------------------------------------------------------------- AWT event thread only

  private void create(String title, KeySink sink) {
    try {
      panel = new FramePanel();
      panel.setBackground(Color.BLACK);
      panel.setPreferredSize(new Dimension(854, 480));
      panel.setFocusable(true);
      // Keys are read from the panel: only a click into the window can focus it, so the human is
      // never typing into the game by accident.
      panel.addKeyListener(
          new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
              forward(event, true, sink);
            }

            @Override
            public void keyReleased(KeyEvent event) {
              forward(event, false, sink);
            }
          });

      JFrame created = new JFrame(title);
      created.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
      // Never take focus when it appears: a window that steals focus swallows the first keypress.
      created.setAutoRequestFocus(false);
      created.setContentPane(panel);
      created.pack();
      created.setLocationByPlatform(true);
      created.setVisible(true);

      frame = created;
      timer = new Timer(REPAINT_PERIOD_MS, event -> drawLatest());
      timer.start();
    } catch (Throwable t) {
      LOG.log(Level.WARNING, "Minecheck: could not build the view window", t);
      frame = null;
    }
  }

  private static void forward(KeyEvent event, boolean down, KeySink sink) {
    int key = glfwKey(event.getKeyCode());
    if (key == GLFW_KEY_UNKNOWN) {
      return;
    }
    try {
      sink.key(key, down);
    } catch (RuntimeException e) {
      LOG.log(Level.WARNING, "Minecheck: the view window could not forward a key", e);
    }
  }

  private void drawLatest() {
    Frame next = pending.getAndSet(null);
    if (next == null || next == painted) {
      return;
    }
    painted = next;
    if (image == null || image.getWidth() != next.width() || image.getHeight() != next.height()) {
      image = new BufferedImage(next.width(), next.height(), BufferedImage.TYPE_INT_RGB);
    }
    image.setRGB(0, 0, next.width(), next.height(), next.pixels(), 0, next.width());
    panel.repaint();
  }

  /** Draws the newest frame scaled to the window, keeping its aspect ratio. */
  private final class FramePanel extends JPanel {

    @Override
    protected void paintComponent(Graphics graphics) {
      Graphics2D g = (Graphics2D) graphics;
      g.setColor(getBackground());
      g.fillRect(0, 0, getWidth(), getHeight());
      BufferedImage current = image;
      if (current == null || getWidth() <= 0 || getHeight() <= 0) {
        return;
      }
      double scale =
          Math.min(
              (double) getWidth() / current.getWidth(), (double) getHeight() / current.getHeight());
      int width = Math.max(1, (int) Math.round(current.getWidth() * scale));
      int height = Math.max(1, (int) Math.round(current.getHeight() * scale));
      g.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.drawImage(
          current, (getWidth() - width) / 2, (getHeight() - height) / 2, width, height, null);
    }
  }

  // ---------------------------------------------------------------- AWT → GLFW keys

  /** GLFW reports no key for a code it does not know; used as "not forwarded". */
  private static final int GLFW_KEY_UNKNOWN = -1;

  /*
   * GLFW's key codes are a stable ABI (glfw3.h), and AWT's coincide with them for letters, digits
   * and space — so only the gaps need naming. Keeping the literals here rather than importing
   * org.lwjgl.glfw means this module stays dependency-free.
   */
  private static final int GLFW_KEY_F1 = 290;
  private static final int GLFW_KEY_ESCAPE = 256;
  private static final int GLFW_KEY_ENTER = 257;
  private static final int GLFW_KEY_TAB = 258;
  private static final int GLFW_KEY_BACKSPACE = 259;
  private static final int GLFW_KEY_DELETE = 261;
  private static final int GLFW_KEY_RIGHT = 262;
  private static final int GLFW_KEY_LEFT = 263;
  private static final int GLFW_KEY_DOWN = 264;
  private static final int GLFW_KEY_UP = 265;
  private static final int GLFW_KEY_LEFT_SHIFT = 340;
  private static final int GLFW_KEY_LEFT_CONTROL = 341;
  private static final int GLFW_KEY_LEFT_ALT = 342;
  private static final int GLFW_KEY_SPACE = 32;

  /**
   * The GLFW key code for an AWT key code, or {@link #GLFW_KEY_UNKNOWN} when the window has no
   * mapping for it (a dead key, an IME, a media key). Only the codes a script can name are mapped:
   * letters, digits, space, the function keys, the arrows and the few modifiers and editing keys a
   * GUI needs.
   */
  private static int glfwKey(int awtKey) {
    if (awtKey >= KeyEvent.VK_A && awtKey <= KeyEvent.VK_Z) {
      return awtKey; // AWT VK_A..VK_Z are GLFW_KEY_A..GLFW_KEY_Z (65…90)
    }
    if (awtKey >= KeyEvent.VK_0 && awtKey <= KeyEvent.VK_9) {
      return awtKey; // and so are the digits (48…57)
    }
    if (awtKey >= KeyEvent.VK_F1 && awtKey <= KeyEvent.VK_F12) {
      return GLFW_KEY_F1 + (awtKey - KeyEvent.VK_F1);
    }
    return switch (awtKey) {
      case KeyEvent.VK_SPACE -> GLFW_KEY_SPACE;
      case KeyEvent.VK_ESCAPE -> GLFW_KEY_ESCAPE;
      case KeyEvent.VK_ENTER -> GLFW_KEY_ENTER;
      case KeyEvent.VK_TAB -> GLFW_KEY_TAB;
      case KeyEvent.VK_BACK_SPACE -> GLFW_KEY_BACKSPACE;
      case KeyEvent.VK_DELETE -> GLFW_KEY_DELETE;
      case KeyEvent.VK_RIGHT -> GLFW_KEY_RIGHT;
      case KeyEvent.VK_LEFT -> GLFW_KEY_LEFT;
      case KeyEvent.VK_DOWN -> GLFW_KEY_DOWN;
      case KeyEvent.VK_UP -> GLFW_KEY_UP;
      case KeyEvent.VK_SHIFT -> GLFW_KEY_LEFT_SHIFT;
      case KeyEvent.VK_CONTROL -> GLFW_KEY_LEFT_CONTROL;
      case KeyEvent.VK_ALT -> GLFW_KEY_LEFT_ALT;
      default -> GLFW_KEY_UNKNOWN;
    };
  }
}
