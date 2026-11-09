package net.minecheck.platform.api;

/**
 * The camera, as the rest of Minecheck sees it: a client-side viewpoint that can be installed,
 * moved and removed, with no Minecraft types in the contract.
 *
 * <p>The pose travels as primitives rather than a shape class because this module is the
 * dependency-free layer — {@code :common} owns the decision of where the camera is, and a loader
 * only carries it out. Degrees and blocks are the game's own units, so a loader converts nothing.
 *
 * <p>Every method is called on the client thread.
 */
public interface CameraPlatform {

  /** Where the camera is and which way it looks, in world coordinates and degrees. */
  record State(double x, double y, double z, float yaw, float pitch) {}

  /**
   * A camera this platform does not have — a dedicated server, or a client whose {@code cameraMode}
   * is {@code off}. Every query answers "nothing here", so callers need no mode check of their own.
   */
  CameraPlatform NONE =
      new CameraPlatform() {
        @Override
        public boolean installed() {
          return false;
        }

        @Override
        public boolean active() {
          return false;
        }

        @Override
        public void registerBindings() {}

        @Override
        public void toggle() {}

        @Override
        public void tick() {}

        @Override
        public void move(double forward, double up, double right) {}

        @Override
        public State pose() {
          return null;
        }
      };

  /** Whether a camera exists in this run at all — the mode is not {@code off}. */
  boolean installed();

  /** Whether the camera is attached and is the client's viewpoint. */
  boolean active();

  /**
   * Register the camera's key bindings, once, at the loader's client-setup point. Inert when the
   * mode is off — the bindings are part of the installed camera, not of the mod.
   */
  void registerBindings();

  /** Attach a detached camera, or detach an attached one. */
  void toggle();

  /** One client tick: read the human's keys, apply the flight model, move the entity. */
  void tick();

  /** Move the camera relative to its own basis, for {@code cameraMove}. */
  void move(double forward, double up, double right);

  /** The current pose, or {@code null} while detached. */
  State pose();
}
