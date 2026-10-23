package com.spacegame;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * MOTHERSHIP.
 *
 * You start in the small cockpit. Open the back door and the rest of the
 * ship is behind it: a corridor, mess, berths, a huge cargo hall, engineering,
 * and an observation room.
 *
 * Controls
 *   WASD          walk
 *   mouse         look
 *   E at the door open / close it
 *   E at an orange rack   put the jetpack on / take it off
 *   Space         jetpack up          (only while wearing it)
 *   Left Shift    drop faster
 *   F at the front seat   fly the whole ship / stand up
 *   Esc           quit
 *
 * Outside the windows there is only the planet and the stars.
 * Almost every object is the same cube, moved and colored.
 * Robot names are drawn with a tiny bitmap font of cubes.
 */
public class Main {
    // Fixed world position of the planet (relative to the ship origin)
    private static final float PLANET_X = -16f;
    private static final float PLANET_Y = 6f;
    private static final float PLANET_Z = -34f;

    private long window;
    private int width = 1280;
    private int height = 720;

    // Player camera / body state (local ship coordinates)
    private final Vector3f position = new Vector3f(0f, 1.6f, 0.5f);
    private final Vector3f lastSafe = new Vector3f(0f, 1.6f, 0.5f);
    private float yaw = -90f;
    private float pitch = -6f;
    private float vy;                    // vertical velocity (jetpack / gravity)
    private final float walkSpeed = 3.4f;
    private final float mouseSensitivity = 0.12f;
    private boolean firstMouse = true;
    private double lastX = width / 2.0;
    private double lastY = height / 2.0;

    // Ship flight mode (when seated at the controls)
    private boolean flying;
    private float shipYaw;
    private float shipPitch;
    private float shipSpeed;
    private final Vector3f shipPos = new Vector3f();
    private final Matrix4f invShip = new Matrix4f();   // inverse transform for world objects

    // Door and jetpack interaction state
    private boolean doorOpen;
    private float doorAnim;
    private boolean eWas;
    private boolean fWas;

    // -1 means the jetpack is on a rack. 0 or 1 means you are wearing that rack's pack.
    private int jetRack = -1;
    private boolean jetOn;
    private final float[][] racks = {{8f, 26f}, {28f, 64f}};

    // OpenGL resources
    private int vao;
    private int vbo;
    private int ebo;
    private int shaderProgram;
    private int fbo;
    private int colorTex;
    private int depthRb;
    private int lowW = 320;
    private int lowH = 180;
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f model = new Matrix4f();
    private final FloatBuffer matBuf = BufferUtils.createFloatBuffer(16);

    // Planet sphere geometry
    private int sphereVao;
    private int sphereVbo;
    private int[] bandFirst;
    private int[] bandCount;
    private static final float[][] BANDS = {
            {0.12f, 0.28f, 0.45f},
            {0.18f, 0.36f, 0.62f},
            {0.79f, 0.63f, 0.35f},
            {0.14f, 0.32f, 0.55f},
            {0.22f, 0.48f, 0.32f},
            {0.85f, 0.82f, 0.72f},
            {0.16f, 0.34f, 0.58f}
    };

    // Geometry lists (all cubes)
    private final List<float[]> shuttle = new ArrayList<>();
    private final List<float[]> shuttleLamps = new ArrayList<>();
    private final List<float[]> stars = new ArrayList<>();
    private final List<Room> rooms = new ArrayList<>();
    private final List<Pad> pads = new ArrayList<>();
    private final List<float[]> pillars = new ArrayList<>();
    private final List<Bot> bots = new ArrayList<>();

    // Sound effects
    private Clip jetClip;
    private Clip doorClip;
    private Clip equipClip;
    private Clip dropClip;
    private Clip[] chirps = new Clip[0];

    // Tiny bitmap font used for robot names (each character is a 5x7 grid of cubes)
    private static final Map<Character, String[]> FONT = new HashMap<>();

    static {
        font('A', "01110", "10001", "10001", "11111", "10001", "10001", "10001");
        font('B', "11110", "10001", "10001", "11110", "10001", "10001", "11110");
        font('E', "11111", "10000", "10000", "11110", "10000", "10000", "11111");
        font('H', "10001", "10001", "10001", "11111", "10001", "10001", "10001");
        font('I', "01110", "00100", "00100", "00100", "00100", "00100", "01110");
        font('L', "10000", "10000", "10000", "10000", "10000", "10000", "11111");
        font('N', "10001", "11001", "10101", "10011", "10001", "10001", "10001");
        font('O', "01110", "10001", "10001", "10001", "10001", "10001", "01110");
        font('P', "11110", "10001", "10001", "11110", "10000", "10000", "10000");
        font('R', "11110", "10001", "10001", "11110", "10100", "10010", "10001");
        font('S', "01111", "10000", "10000", "01110", "00001", "00001", "11110");
        font('T', "11111", "00100", "00100", "00100", "00100", "00100", "00100");
        font('X', "10001", "10001", "01010", "00100", "01010", "10001", "10001");
        font('Y', "10001", "10001", "01010", "00100", "00100", "00100", "00100");
        font('4', "00110", "01010", "10010", "11111", "00010", "00010", "00010");
        font('9', "01110", "10001", "10001", "01111", "00001", "10001", "01110");
        font('-', "00000", "00000", "00000", "11111", "00000", "00000", "00000");
    }

    private static void font(char c, String... rows) {
        FONT.put(c, rows);
    }

    public static void main(String[] args) {
        new Main().run();
    }

    private void run() {
        init();
        loop();
        cleanup();
    }

    /** Window, OpenGL context, shaders, geometry, audio, and input callbacks. */
    private void init() {
        org.lwjgl.system.Configuration.GLFW_LIBRARY_NAME.set("glfw_async");
        org.lwjgl.system.Configuration.GLFW_CHECK_THREAD0.set(false);

        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("Unable to initialize GLFW");

        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

        // Fullscreen on the primary monitor
        long monitor = glfwGetPrimaryMonitor();
        org.lwjgl.glfw.GLFWVidMode mode = glfwGetVideoMode(monitor);
        window = glfwCreateWindow(mode.width(), mode.height(), "MOTHERSHIP", monitor, NULL);
        if (window == NULL) throw new RuntimeException("Failed to create GLFW window");

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED);

        GL.createCapabilities();
        glEnable(GL_DEPTH_TEST);
        glClearColor(0.02f, 0.03f, 0.06f, 1f);

        int[] fw = new int[1];
        int[] fh = new int[1];
        glfwGetFramebufferSize(window, fw, fh);
        width = Math.max(fw[0], 1);
        height = Math.max(fh[0], 1);
        allocPixelTarget(width, height);

        shaderProgram = createShaderProgram();
        createCube();
        createSphere();
        projection.perspective((float) Math.toRadians(68f), (float) lowW / lowH, 0.08f, 260f);

        buildShuttle();
        buildInterior();
        defineVolumes();
        buildStars();
        initBots();
        initAudio();

        // Mouse look (also steers the ship when flying)
        glfwSetCursorPosCallback(window, (win, xpos, ypos) -> {
            if (firstMouse) {
                lastX = xpos;
                lastY = ypos;
                firstMouse = false;
            }
            float xoff = (float) (xpos - lastX) * mouseSensitivity;
            float yoff = (float) (lastY - ypos) * mouseSensitivity;
            lastX = xpos;
            lastY = ypos;
            if (flying) {
                shipYaw += xoff * 0.55f;
                shipPitch -= yoff * 0.35f;
                shipPitch = clamp(shipPitch, -32f, 32f);
            } else {
                yaw += xoff;
                pitch += yoff;
                pitch = clamp(pitch, -70f, 70f);
            }
        });

        glfwSetFramebufferSizeCallback(window, (win, w, h) -> {
            if (w <= 0 || h <= 0) return;
            width = w;
            height = h;
            allocPixelTarget(w, h);
            projection.identity().perspective((float) Math.toRadians(68f), (float) lowW / lowH, 0.08f, 260f);
        });
    }

    // The cockpit you already know. The back wall is now a doorway into the rest of the ship.
    private void buildShuttle() {
        // Floor tiles
        for (int ix = -4; ix <= 4; ix++) {
            for (int iz = -6; iz <= 18; iz++) {
                boolean alt = ((ix + iz) & 1) == 0;
                s(ix * 0.42f, 0f, iz * 0.42f, 0.42f, 0.08f, 0.42f,
                        alt ? 0.10f : 0.16f, alt ? 0.12f : 0.18f, alt ? 0.16f : 0.22f);
            }
        }
        // Console lights
        for (int i = -3; i <= 3; i++) {
            boolean amber = (i & 1) == 0;
            s(i * 0.28f, 0.06f, -1.05f, 0.22f, 0.03f, 0.14f,
                    amber ? 0.90f : 0.08f, amber ? 0.64f : 0.08f, amber ? 0.23f : 0.10f);
        }
        // Ceiling and structural beams
        s(0f, 2.55f, 0.5f, 4.1f, 0.10f, 7.6f, 0.11f, 0.14f, 0.18f);
        s(0f, 2.55f, 5.9f, 4.1f, 0.10f, 3.6f, 0.13f, 0.12f, 0.11f);
        for (float z = -2.8f; z <= 3.4f; z += 1.1f) s(0f, 2.42f, z, 3.9f, 0.08f, 0.10f, 0.23f, 0.28f, 0.34f);
        s(0f, 2.46f, -1.4f, 1.5f, 0.05f, 0.12f, 0.75f, 0.85f, 0.95f);
        s(0f, 2.46f, 0.6f, 1.5f, 0.05f, 0.12f, 0.75f, 0.85f, 0.95f);
        s(0f, 2.46f, 2.2f, 1.5f, 0.05f, 0.12f, 0.75f, 0.85f, 0.95f);
        s(-1.5f, 2.25f, 0.4f, 0.08f, 0.08f, 5.0f, 0.42f, 0.46f, 0.52f);
        s(1.5f, 2.18f, 0.2f, 0.08f, 0.08f, 4.2f, 0.77f, 0.48f, 0.23f);
        hullSide(-2.05f);
        hullSide(2.05f);
        // Front windshield frame
        s(-1.65f, 1.35f, -3.35f, 0.7f, 2.4f, 0.16f, 0.16f, 0.20f, 0.26f);
        s(1.65f, 1.35f, -3.35f, 0.7f, 2.4f, 0.16f, 0.16f, 0.20f, 0.26f);
        s(0f, 2.35f, -3.35f, 2.7f, 0.40f, 0.16f, 0.16f, 0.20f, 0.26f);
        s(0f, 0.78f, -3.2f, 2.5f, 0.20f, 0.18f, 0.10f, 0.13f, 0.17f);
        s(-0.8f, 1.85f, -2.95f, 0.08f, 1.1f, 0.08f, 0.29f, 0.35f, 0.42f);
        s(0.8f, 1.85f, -2.95f, 0.08f, 1.1f, 0.08f, 0.29f, 0.35f, 0.42f);
        s(0f, 2.35f, -2.5f, 0.08f, 0.08f, 1.5f, 0.29f, 0.35f, 0.42f);
        // Rear bulkhead
        s(-1.5f, 1.3f, 3.9f, 1.1f, 2.4f, 0.16f, 0.16f, 0.20f, 0.26f);
        s(1.5f, 1.3f, 3.9f, 1.1f, 2.4f, 0.16f, 0.16f, 0.20f, 0.26f);
        s(0f, 2.35f, 3.9f, 1.9f, 0.35f, 0.16f, 0.16f, 0.20f, 0.26f);
        s(0f, 2.05f, 3.96f, 0.9f, 0.10f, 0.04f, 0.90f, 0.64f, 0.23f);
        // Console / dashboard
        s(0f, 0.62f, -1.95f, 2.4f, 0.7f, 0.8f, 0.18f, 0.22f, 0.28f);
        s(0f, 1.02f, -2.22f, 2.25f, 0.12f, 0.38f, 0.24f, 0.28f, 0.36f);
        s(0f, 1.28f, -2.35f, 2.4f, 0.08f, 0.18f, 0.07f, 0.09f, 0.12f);
        s(-0.5f, 1.16f, -2.06f, 0.62f, 0.40f, 0.04f, 0.02f, 0.07f, 0.05f);
        s(0.52f, 1.16f, -2.06f, 0.62f, 0.40f, 0.04f, 0.08f, 0.04f, 0.02f);
        s(-0.5f, 1.16f, -2.08f, 0.72f, 0.50f, 0.05f, 0.06f, 0.08f, 0.10f);
        s(0.52f, 1.16f, -2.08f, 0.72f, 0.50f, 0.05f, 0.06f, 0.08f, 0.10f);
        float[][] cols = {
                {0.90f, 0.64f, 0.23f},
                {0.18f, 0.77f, 0.71f},
                {0.84f, 0.27f, 0.27f},
                {0.18f, 0.77f, 0.71f},
                {0.90f, 0.64f, 0.23f}
        };
        for (int i = 0; i < cols.length; i++) {
            sl(-0.7f + i * 0.35f, 1.08f, -1.62f, 0.12f, 0.08f, 0.12f, cols[i][0], cols[i][1], cols[i][2]);
        }
        sl(0f, 1.38f, -2.22f, 0.28f, 0.07f, 0.08f, 0.90f, 0.55f, 0.15f);
        s(0f, 0.95f, -1.55f, 0.08f, 0.32f, 0.08f, 0.10f, 0.12f, 0.16f);
        s(0f, 1.12f, -1.48f, 0.62f, 0.07f, 0.10f, 0.81f, 0.84f, 0.88f);
        s(-0.28f, 1.12f, -1.42f, 0.08f, 0.16f, 0.08f, 0.81f, 0.84f, 0.88f);
        s(0.28f, 1.12f, -1.42f, 0.08f, 0.16f, 0.08f, 0.81f, 0.84f, 0.88f);
        s(1.15f, 0.95f, -1.55f, 0.26f, 0.16f, 0.36f, 0.11f, 0.14f, 0.18f);
        s(1.15f, 1.14f, -1.42f, 0.06f, 0.28f, 0.06f, 0.90f, 0.64f, 0.23f);
        s(-1.65f, 0.85f, -1.15f, 0.5f, 0.7f, 1.2f, 0.15f, 0.18f, 0.24f);
        s(1.65f, 0.85f, -1.15f, 0.5f, 0.7f, 1.2f, 0.15f, 0.18f, 0.24f);
        s(-1.92f, 1.15f, -1.15f, 0.04f, 0.16f, 0.55f, 0.18f, 0.77f, 0.71f);
        s(1.92f, 1.15f, -1.15f, 0.04f, 0.16f, 0.55f, 0.90f, 0.64f, 0.23f);
        seat(-0.95f);
        seat(0.95f);
        // Miscellaneous props
        s(-1.75f, 0.55f, 2.3f, 0.16f, 0.55f, 0.16f, 0.77f, 0.27f, 0.23f);
        s(-1.55f, 0.45f, 3.05f, 0.16f, 0.7f, 0.16f, 0.56f, 0.63f, 0.71f);
        s(-1.85f, 0.45f, 3.05f, 0.16f, 0.7f, 0.16f, 0.56f, 0.63f, 0.71f);
        s(-1.25f, 0.85f, 2.6f, 0.36f, 0.55f, 0.22f, 0.77f, 0.36f, 0.20f);
        s(-1.25f, 0.28f, 2.6f, 0.12f, 0.4f, 0.12f, 0.77f, 0.36f, 0.20f);
        s(-1.08f, 0.28f, 2.6f, 0.12f, 0.4f, 0.12f, 0.77f, 0.36f, 0.20f);
        s(-1.42f, 0.8f, 2.6f, 0.12f, 0.4f, 0.12f, 0.77f, 0.36f, 0.20f);
        s(-1.08f, 0.8f, 2.55f, 0.12f, 0.4f, 0.12f, 0.77f, 0.36f, 0.20f);
        s(-1.25f, 1.28f, 2.6f, 0.26f, 0.24f, 0.24f, 0.84f, 0.87f, 0.90f);
        s(-1.25f, 1.28f, 2.48f, 0.18f, 0.08f, 0.04f, 0.10f, 0.16f, 0.22f);
        s(-2.05f, 1.3f, 5.9f, 0.16f, 2.5f, 3.6f, 0.20f, 0.16f, 0.13f);
        s(2.05f, 1.3f, 5.9f, 0.16f, 2.5f, 3.6f, 0.20f, 0.16f, 0.13f);
        // Frame around the opening into the big ship. The center is empty on purpose.
        s(-1.55f, 1.15f, 7.65f, 0.45f, 2.2f, 0.18f, 0.18f, 0.15f, 0.13f);
        s(1.55f, 1.15f, 7.65f, 0.45f, 2.2f, 0.18f, 0.18f, 0.15f, 0.13f);
        s(0f, 2.35f, 7.65f, 2.7f, 0.32f, 0.18f, 0.18f, 0.15f, 0.13f);
        s(1.55f, 1.45f, 6.7f, 0.9f, 0.7f, 0.06f, 0.05f, 0.08f, 0.12f);
        s(1.55f, 1.45f, 6.62f, 1.05f, 0.85f, 0.06f, 0.28f, 0.24f, 0.20f);
        sl(0f, 2.35f, 5.4f, 0.7f, 0.06f, 0.16f, 0.85f, 0.72f, 0.40f);
        sl(0f, 2.35f, 6.6f, 0.7f, 0.06f, 0.16f, 0.85f, 0.72f, 0.40f);
        s(-1.35f, 0.45f, 5.3f, 0.7f, 0.7f, 0.7f, 0.34f, 0.27f, 0.18f);
        s(-1.2f, 1.05f, 5.45f, 0.45f, 0.45f, 0.45f, 0.45f, 0.32f, 0.20f);
        s(1.15f, 0.55f, 5.6f, 0.9f, 0.9f, 0.9f, 0.25f, 0.28f, 0.22f);
        s(0.2f, 0.35f, 6.7f, 0.6f, 0.5f, 0.8f, 0.40f, 0.22f, 0.16f);
        s(-1.5f, 0.7f, 6.9f, 0.18f, 1.2f, 0.18f, 0.55f, 0.58f, 0.62f);
        s(-1.15f, 0.7f, 6.9f, 0.18f, 1.2f, 0.18f, 0.55f, 0.58f, 0.62f);
    }

    // Everything aft of the cockpit. Big on purpose: the cargo hall is the size of a warehouse.
    private void buildInterior() {
        // Main corridor
        floor(-2.2f, 2.2f, 7.4f, 40.5f, 1.15f, 0.11f, 0.13f, 0.16f);
        s(0f, 0.08f, 24f, 0.18f, 0.06f, 32f, 0.90f, 0.64f, 0.23f);
        s(2.55f, 2.3f, 12f, 0.18f, 4.6f, 9f, 0.14f, 0.16f, 0.20f);
        s(2.55f, 2.3f, 38f, 0.18f, 4.6f, 5f, 0.14f, 0.16f, 0.20f);
        s(-2.55f, 2.3f, 12f, 0.18f, 4.6f, 9f, 0.14f, 0.16f, 0.20f);
        s(-2.55f, 2.3f, 38f, 0.18f, 4.6f, 5f, 0.14f, 0.16f, 0.20f);
        s(0f, 4.55f, 24f, 5.2f, 0.16f, 33f, 0.10f, 0.12f, 0.15f);
        s(0f, 3.3f, 17f, 2.4f, 0.16f, 0.16f, 0.23f, 0.26f, 0.32f);
        s(0f, 3.3f, 35f, 2.4f, 0.16f, 0.16f, 0.23f, 0.26f, 0.32f);
        for (float z = 10f; z <= 38f; z += 4f) {
            sl(-1.6f, 4.35f, z, 0.7f, 0.08f, 0.25f, 0.85f, 0.75f, 0.45f);
            sl(1.6f, 4.35f, z, 0.7f, 0.08f, 0.25f, 0.85f, 0.75f, 0.45f);
        }

        // Mess, on the right.
        floor(2.3f, 14.5f, 17f, 35f, 1.6f, 0.16f, 0.13f, 0.11f);
        s(14.6f, 2.2f, 26f, 0.2f, 4.4f, 18f, 0.18f, 0.14f, 0.12f);
        s(8.5f, 2.2f, 17f, 12f, 4.4f, 0.2f, 0.18f, 0.14f, 0.12f);
        s(8.5f, 2.2f, 35f, 12f, 4.4f, 0.2f, 0.18f, 0.14f, 0.12f);
        s(8.5f, 4.4f, 26f, 12.2f, 0.16f, 18f, 0.14f, 0.11f, 0.10f);
        s(6f, 0.85f, 24f, 1.6f, 0.12f, 1.6f, 0.35f, 0.24f, 0.16f);
        s(6f, 0.85f, 29f, 1.6f, 0.12f, 1.6f, 0.35f, 0.24f, 0.16f);
        s(11f, 0.85f, 26f, 1.6f, 0.12f, 3.2f, 0.35f, 0.24f, 0.16f);
        sl(8f, 4.2f, 26f, 2.2f, 0.08f, 0.4f, 0.9f, 0.7f, 0.35f);

        // Berths, on the left.
        floor(-14.5f, -2.3f, 17f, 35f, 1.6f, 0.12f, 0.13f, 0.16f);
        s(-14.6f, 2.1f, 26f, 0.2f, 4.2f, 18f, 0.13f, 0.15f, 0.18f);
        s(-8.5f, 2.1f, 17f, 12f, 4.2f, 0.2f, 0.13f, 0.15f, 0.18f);
        s(-8.5f, 2.1f, 35f, 12f, 4.2f, 0.2f, 0.13f, 0.15f, 0.18f);
        s(-8.5f, 4.2f, 26f, 12.2f, 0.16f, 18f, 0.11f, 0.12f, 0.15f);
        for (int i = 0; i < 3; i++) {
            float z = 20f + i * 4.5f;
            s(-10f, 0.45f, z, 2.2f, 0.3f, 1.1f, 0.22f, 0.24f, 0.30f);
            s(-10f, 1.35f, z, 2.2f, 0.25f, 1.1f, 0.28f, 0.30f, 0.36f);
            s(-11f, 0.9f, z, 0.12f, 1.5f, 1.1f, 0.18f, 0.20f, 0.24f);
        }
        sl(-6f, 4.0f, 26f, 1.4f, 0.08f, 0.3f, 0.45f, 0.65f, 0.9f);

        // Cargo hall. No solid roof: look up and you are looking into space.
        floor(-25f, 25f, 40f, 106f, 2.0f, 0.10f, 0.11f, 0.13f);
        s(0f, 0.1f, 74f, 0.4f, 0.06f, 64f, 0.75f, 0.55f, 0.2f);
        s(25.2f, 6f, 46f, 0.3f, 12f, 12f, 0.15f, 0.17f, 0.21f);
        s(25.2f, 6f, 90f, 0.3f, 12f, 28f, 0.15f, 0.17f, 0.21f);
        s(-25.2f, 6f, 46f, 0.3f, 12f, 12f, 0.15f, 0.17f, 0.21f);
        s(-25.2f, 6f, 90f, 0.3f, 12f, 28f, 0.15f, 0.17f, 0.21f);
        s(0f, 6f, 106.2f, 50f, 12f, 0.3f, 0.13f, 0.15f, 0.18f);
        s(0f, 3.2f, 40.2f, 8f, 0.25f, 0.25f, 0.9f, 0.64f, 0.23f);
        for (float z = 54f; z <= 100f; z += 16f) {
            column(12f, z);
            column(-12f, z);
            pillars.add(new float[]{12f, z, 1.05f});
            pillars.add(new float[]{-12f, z, 1.05f});
            s(0f, 12.2f, z, 50f, 0.35f, 0.5f, 0.22f, 0.25f, 0.30f);
            sl(-18f, 11.6f, z, 1.2f, 0.15f, 0.4f, 0.9f, 0.75f, 0.4f);
            sl(0f, 11.6f, z, 1.2f, 0.15f, 0.4f, 0.9f, 0.75f, 0.4f);
            sl(18f, 11.6f, z, 1.2f, 0.15f, 0.4f, 0.9f, 0.75f, 0.4f);
        }
        // Catwalks. You can only reach them with the jetpack.
        s(11f, 5.9f, 75f, 6f, 0.2f, 40f, 0.20f, 0.22f, 0.26f);
        s(-11f, 5.9f, 75f, 6f, 0.2f, 40f, 0.20f, 0.22f, 0.26f);
        s(13.7f, 6.7f, 75f, 0.12f, 0.7f, 40f, 0.35f, 0.38f, 0.42f);
        s(-13.7f, 6.7f, 75f, 0.12f, 0.7f, 40f, 0.35f, 0.38f, 0.42f);
        s(8f, 1.2f, 60f, 2.2f, 2.2f, 2.2f, 0.28f, 0.22f, 0.16f);
        s(-8f, 0.8f, 88f, 1.6f, 1.4f, 1.6f, 0.22f, 0.24f, 0.2f);
        s(4f, 1.6f, 96f, 2.4f, 3f, 1.8f, 0.18f, 0.2f, 0.24f);

        // Engineering, off the right side of the hall.
        floor(25f, 33.5f, 52f, 74f, 1.7f, 0.12f, 0.12f, 0.11f);
        s(33.6f, 3f, 63f, 0.25f, 6f, 22f, 0.16f, 0.14f, 0.12f);
        s(29f, 3f, 52f, 9f, 6f, 0.25f, 0.16f, 0.14f, 0.12f);
        s(29f, 3f, 74f, 9f, 6f, 0.25f, 0.16f, 0.14f, 0.12f);
        s(29f, 5.9f, 63f, 9f, 0.18f, 22f, 0.12f, 0.11f, 0.1f);
        s(30f, 1.6f, 58f, 1.4f, 3f, 1.4f, 0.1f, 0.1f, 0.12f);
        s(30f, 1.6f, 68f, 1.4f, 3f, 1.4f, 0.1f, 0.1f, 0.12f);
        sl(30f, 3.3f, 58f, 0.7f, 0.25f, 0.7f, 0.95f, 0.4f, 0.12f);
        sl(30f, 3.3f, 68f, 0.7f, 0.25f, 0.7f, 0.95f, 0.45f, 0.15f);

        // Observation, off the left side. The far wall is a window, not a solid plate.
        floor(-33.5f, -25f, 52f, 74f, 1.7f, 0.11f, 0.13f, 0.16f);
        s(-29f, 3f, 52f, 9f, 6f, 0.25f, 0.14f, 0.16f, 0.2f);
        s(-29f, 3f, 74f, 9f, 6f, 0.25f, 0.14f, 0.16f, 0.2f);
        s(-29f, 5.9f, 63f, 9f, 0.18f, 22f, 0.1f, 0.12f, 0.15f);
        s(-33.3f, 3f, 54.5f, 0.2f, 6f, 0.25f, 0.3f, 0.34f, 0.4f);
        s(-33.3f, 3f, 71.5f, 0.2f, 6f, 0.25f, 0.3f, 0.34f, 0.4f);
        s(-33.3f, 5.7f, 63f, 0.2f, 0.25f, 18f, 0.3f, 0.34f, 0.4f);
        s(-33.3f, 0.4f, 63f, 0.2f, 0.25f, 18f, 0.3f, 0.34f, 0.4f);
        s(-28f, 0.7f, 63f, 1.2f, 0.7f, 6f, 0.2f, 0.22f, 0.26f);
    }

    /** Procedural tiled floor. */
    private void floor(float x0, float x1, float z0, float z1, float step, float r, float g, float b) {
        int ix = 0;
        for (float x = x0; x <= x1; x += step) {
            int iz = 0;
            for (float z = z0; z <= z1; z += step) {
                boolean alt = ((ix + iz) & 1) == 0;
                s(x, 0f, z, step, 0.12f, step, r * (alt ? 0.75f : 1.15f), g * (alt ? 0.75f : 1.15f), b * (alt ? 0.8f : 1.1f));
                iz++;
            }
            ix++;
        }
    }

    private void column(float x, float z) {
        s(x, 6f, z, 1.3f, 12f, 1.3f, 0.18f, 0.2f, 0.24f);
        s(x, 11.4f, z, 2.2f, 0.4f, 2.2f, 0.24f, 0.27f, 0.32f);
    }

    // Invisible boxes the player is allowed to be inside. ceil is max eye height.
    private void defineVolumes() {
        rooms.add(new Room(-1.45f, 1.45f, 4.25f, 7.9f, 2.3f));          // cockpit
        rooms.add(new Room(-2.3f, 2.3f, 7.5f, 41f, 4.5f));               // corridor
        rooms.add(new Room(2.1f, 14.3f, 17.2f, 34.8f, 4.2f));            // mess
        rooms.add(new Room(-14.3f, -2.1f, 17.2f, 34.8f, 4.0f));          // berths
        rooms.add(new Room(-24.6f, 24.6f, 39.2f, 105.5f, 12.8f));        // cargo hall
        rooms.add(new Room(22.5f, 33.2f, 52.3f, 73.6f, 5.6f));           // engineering
        rooms.add(new Room(-33.0f, -22.5f, 52.3f, 73.6f, 5.6f));         // observation
        pads.add(new Pad(8.2f, 13.8f, 56f, 94f, 7.5f));                  // right catwalk
        pads.add(new Pad(-13.8f, -8.2f, 56f, 94f, 7.5f));                // left catwalk
    }

    /** Spawn the roaming robots and their chirp sounds. */
    private void initBots() {
        bots.add(new Bot("REX-4", 0.85f, 0.45f, 0.2f, 0f, 420f, new float[][]{{0f, 12f}, {0f, 36f}}));
        bots.add(new Bot("BOLT", 0.2f, 0.75f, 0.7f, 0f, 510f, new float[][]{{5f, 22f}, {12f, 22f}, {12f, 31f}, {5f, 31f}}));
        bots.add(new Bot("NYX", 0.45f, 0.5f, 0.85f, 0f, 330f, new float[][]{{-5f, 21f}, {-12f, 21f}, {-12f, 32f}, {-5f, 32f}}));
        bots.add(new Bot("ASH-9", 0.8f, 0.75f, 0.3f, 0f, 250f, new float[][]{{-8f, 52f}, {8f, 52f}, {8f, 98f}, {-8f, 98f}}));
        bots.add(new Bot("PIP", 0.9f, 0.35f, 0.55f, 2.3f, 680f, new float[][]{{26f, 58f}, {26f, 70f}, {30f, 64f}}));
        chirps = new Clip[bots.size()];
        for (int i = 0; i < bots.size(); i++) chirps[i] = loadBeep(0.09f, bots.get(i).chirp);
    }

    private void buildStars() {
        java.util.Random rng = new java.util.Random(11);
        for (int i = 0; i < 220; i++) {
            float sx = -240f + rng.nextFloat() * 480f;
            float sy = -80f + rng.nextFloat() * 200f;
            float sz = -460f + rng.nextFloat() * 520f;
            float sc = 0.3f + rng.nextFloat() * 0.5f;
            stars.add(new float[]{sx, sy, sz, sc});
        }
    }

    private void hullSide(float x) {
        float side = Math.signum(x);
        s(x, 1.2f, -2.9f, 0.16f, 2.4f, 1.2f, 0.17f, 0.21f, 0.27f);
        s(x, 1.2f, 2.5f, 0.16f, 2.4f, 2.4f, 0.17f, 0.21f, 0.27f);
        s(x, 0.4f, -0.4f, 0.16f, 0.8f, 3.4f, 0.17f, 0.21f, 0.27f);
        s(x, 2.15f, -0.4f, 0.16f, 0.7f, 3.4f, 0.20f, 0.24f, 0.31f);
        s(x + side * 0.02f, 1.3f, -1.9f, 0.06f, 0.08f, 0.4f, 0.08f, 0.10f, 0.13f);
        s(x + side * 0.02f, 1.3f, 1.1f, 0.06f, 0.08f, 0.4f, 0.08f, 0.10f, 0.13f);
    }

    private void seat(float x) {
        s(x, 0.42f, 0.85f, 0.6f, 0.14f, 0.6f, 0.36f, 0.23f, 0.16f);
        s(x, 0.85f, 1.12f, 0.6f, 0.7f, 0.14f, 0.36f, 0.23f, 0.16f);
        s(x, 1.25f, 1.14f, 0.26f, 0.14f, 0.14f, 0.23f, 0.15f, 0.11f);
    }

    /** Add a solid cube to the shuttle geometry. */
    private void s(float x, float y, float z, float sx, float sy, float sz, float r, float g, float b) {
        shuttle.add(new float[]{x, y, z, sx, sy, sz, r, g, b});
    }

    /** Add a lamp (pulsing) cube. */
    private void sl(float x, float y, float z, float sx, float sy, float sz, float r, float g, float b) {
        shuttleLamps.add(new float[]{x, y, z, sx, sy, sz, r, g, b});
    }

    /** Low-resolution render target for the retro pixel look. */
    private void allocPixelTarget(int w, int hgt) {
        lowW = Math.max(160, w / 5);
        lowH = Math.max(90, hgt / 5);
        if (fbo != 0) {
            glDeleteFramebuffers(fbo);
            glDeleteTextures(colorTex);
            glDeleteRenderbuffers(depthRb);
        }
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        colorTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colorTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, lowW, lowH, 0, GL_RGB, GL_UNSIGNED_BYTE, 0L);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTex, 0);
        depthRb = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depthRb);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, lowW, lowH);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthRb);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("Pixel target failed");
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /** Main game loop. */
    private void loop() {
        double last = glfwGetTime();
        while (!glfwWindowShouldClose(window)) {
            double now = glfwGetTime();
            float dt = (float) Math.min(now - last, 0.05);
            last = now;

            processInput(dt, now);
            // Animate the door
            float doorTarget = doorOpen ? 1f : 0f;
            doorAnim += Math.signum(doorTarget - doorAnim) * dt * 1.6f;
            if (Math.abs(doorTarget - doorAnim) < dt * 1.6f) doorAnim = doorTarget;
            updateBots(dt, now);
            updateShipMatrix();

            // Camera
            Vector3f front = getFront();
            Vector3f eye = new Vector3f(position.x, position.y + (float) Math.sin(now * 9.0) * 0.012f, position.z);
            view.identity().lookAt(eye, new Vector3f(eye).add(front), new Vector3f(0f, 1f, 0f));

            // Render to low-res FBO then blit with nearest-neighbor (pixelated look)
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glViewport(0, 0, lowW, lowH);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            glUseProgram(shaderProgram);
            glBindVertexArray(vao);

            float pulse = (float) (0.45 + 0.55 * Math.max(0.0, Math.sin(now * 4.0)));
            drawList(shuttle);
            drawLamps(shuttleLamps, pulse);
            drawDoor();
            drawScreens(now);
            drawRacks(pulse);
            drawBots(now);
            // Worn jetpack indicator
            if (jetRack >= 0) draw(1.32f, 1.22f, -1.92f, 0.09f, 0.09f, 0.04f, 0.15f, 0.95f, 0.4f);
            if (jetOn) {
                draw(position.x, 0.25f, position.z, 0.4f, 0.28f, 0.4f, 0.95f, 0.4f, 0.1f);
                draw(position.x, 0.55f, position.z, 0.18f, 0.4f, 0.18f, 1f, 0.85f, 0.35f);
            }
            drawStars();
            drawPlanet();

            // Upscale to the real window
            glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
            glBlitFramebuffer(0, 0, lowW, lowH, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);

            glfwSwapBuffers(window);
            glfwPollEvents();
        }
    }

    /** Inverse ship transform so stars and planet stay fixed in world space. */
    private void updateShipMatrix() {
        invShip.identity()
                .rotateX((float) -Math.toRadians(shipPitch))
                .rotateY((float) -Math.toRadians(shipYaw))
                .translate(-shipPos.x, -shipPos.y, -shipPos.z);
    }

    /** Move bots along their paths and chirp when the player is nearby. */
    private void updateBots(float dt, double now) {
        for (int i = 0; i < bots.size(); i++) {
            Bot b = bots.get(i);
            float tx = b.path[b.wp][0];
            float tz = b.path[b.wp][1];
            float dx = tx - b.pos.x;
            float dz = tz - b.pos.z;
            float dist = (float) Math.sqrt(dx * dx + dz * dz);
            if (dist < 0.3f) b.wp = (b.wp + 1) % b.path.length;
            else {
                b.pos.x += dx / dist * 1.35f * dt;
                b.pos.z += dz / dist * 1.35f * dt;
                b.yaw = (float) Math.toDegrees(Math.atan2(dx, dz));
            }
            b.pos.y = b.hover > 0f ? b.hover + (float) Math.sin(now * 2.2) * 0.18f : 0f;
            float pdx = position.x - b.pos.x;
            float pdz = position.z - b.pos.z;
            if (pdx * pdx + pdz * pdz < 49f && now > b.nextChirp) {
                play(i < chirps.length ? chirps[i] : null);
                b.nextChirp = (float) now + 2.4f;
            }
        }
    }

    private void drawList(List<float[]> list) {
        glBindVertexArray(vao);
        for (float[] b : list) draw(b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8]);
    }

    private void drawLamps(List<float[]> list, float pulse) {
        glBindVertexArray(vao);
        for (float[] b : list) draw(b[0], b[1], b[2], b[3], b[4], b[5], b[6] * pulse, b[7] * pulse, b[8] * pulse);
    }

    /** Sliding double doors. */
    private void drawDoor() {
        glBindVertexArray(vao);
        float slide = doorAnim * 0.62f;
        draw(-0.30f - slide, 1.22f, 3.98f, 0.52f, 1.75f, 0.08f, 0.42f, 0.24f, 0.16f);
        draw(0.30f + slide, 1.22f, 3.98f, 0.52f, 1.75f, 0.08f, 0.42f, 0.24f, 0.16f);
        // Status light
        draw(0f, 2.18f, 3.84f, 0.10f, 0.10f, 0.06f, doorOpen ? 0.15f : 0.85f, doorOpen ? 0.85f : 0.15f, 0.12f);
    }

    /** Animated cockpit instrument screens. */
    private void drawScreens(double now) {
        glBindVertexArray(vao);
        float scroll = shipYaw / 360f;
        for (int i = 0; i < 9; i++) {
            float u = i / 9f - scroll;
            u = u - (float) Math.floor(u);
            float x = -0.76f + u * 0.52f;
            float hh = (i % 3 == 0) ? 0.16f : 0.08f;
            draw(x, 1.24f, -1.97f, 0.025f, hh, 0.02f, 0.45f, 0.95f, 0.62f);
        }
        // Planet bearing indicator
        float rel = wrap(bearing(PLANET_X, PLANET_Z) - shipYaw);
        float bx = -0.50f + clamp(rel / 80f, -1f, 1f) * 0.22f;
        float dist = distance(PLANET_X, PLANET_Y, PLANET_Z);
        float by = 1.02f + clamp(1f - dist / 180f, 0f, 1f) * 0.16f;
        draw(bx, by, -1.97f, 0.07f, 0.07f, 0.02f, 0.95f, 0.92f, 0.45f);
        draw(-0.50f, 1.08f, -1.97f, 0.04f, 0.04f, 0.02f, 0.85f, 1f, 0.85f);
        // Thrust bars
        float thrust = clamp(Math.abs(shipSpeed) / 22f, 0f, 1f);
        for (int i = 0; i < 4; i++) {
            float wobble = 0.85f + 0.15f * (float) Math.sin(now * 6.0 + i);
            float hh = 0.05f + thrust * (0.10f + i * 0.05f) * wobble;
            draw(0.28f + i * 0.14f, 1.00f + hh * 0.5f, -1.97f, 0.08f, hh, 0.02f, 0.95f, 0.55f, 0.12f);
        }
        if (flying) draw(0.72f, 1.28f, -1.97f, 0.08f, 0.08f, 0.02f, 0.2f, 0.95f, 0.45f);
    }

    /** Jetpack storage racks. */
    private void drawRacks(float pulse) {
        glBindVertexArray(vao);
        for (int i = 0; i < racks.length; i++) {
            float x = racks[i][0];
            float z = racks[i][1];
            draw(x, 0.8f, z, 0.3f, 1.5f, 0.3f, 0.12f, 0.13f, 0.16f);
            if (jetRack != i) {
                draw(x, 1.35f, z, 0.58f, 0.72f, 0.28f, 0.9f, 0.38f, 0.12f);
                draw(x, 1.75f, z, 0.16f, 0.16f, 0.16f, 0.2f * pulse, 0.95f * pulse, 0.85f * pulse);
            }
        }
    }

    /** Draw all robots, including their names when close. */
    private void drawBots(double now) {
        glBindVertexArray(vao);
        for (Bot b : bots) {
            float bob = b.hover > 0f ? 0f : (float) Math.sin(now * 7.0) * 0.12f;
            float yr = (float) Math.toRadians(b.yaw);
            float fx = (float) Math.sin(yr);
            float fz = (float) Math.cos(yr);
            float rx = (float) Math.cos(yr);
            float rz = (float) -Math.sin(yr);
            float y = b.pos.y;
            // Body
            draw(b.pos.x, y + 0.85f, b.pos.z, 0.55f, 0.7f, 0.35f, b.r, b.g, b.b);
            draw(b.pos.x, y + 1.4f, b.pos.z, 0.38f, 0.32f, 0.32f, b.r * 0.8f, b.g * 0.8f, b.b * 0.8f);
            // Eye
            draw(b.pos.x + fx * 0.18f, y + 1.42f, b.pos.z + fz * 0.18f, 0.1f, 0.08f, 0.06f, 0.9f, 0.95f, 1f);
            // Antenna
            draw(b.pos.x, y + 1.7f, b.pos.z, 0.06f, 0.22f, 0.06f, 0.7f, 0.72f, 0.75f);
            // Legs
            draw(b.pos.x + rx * 0.16f + fx * bob, y + 0.35f, b.pos.z + rz * 0.16f + fz * bob, 0.14f, 0.55f, 0.14f, 0.15f, 0.16f, 0.18f);
            draw(b.pos.x - rx * 0.16f - fx * bob, y + 0.35f, b.pos.z - rz * 0.16f - fz * bob, 0.14f, 0.55f, 0.14f, 0.15f, 0.16f, 0.18f);
            // Nameplate when player is close
            float pdx = position.x - b.pos.x;
            float pdy = position.y - (y + 1.5f);
            float pdz = position.z - b.pos.z;
            if (pdx * pdx + pdy * pdy + pdz * pdz < 28f * 28f) drawName(b.name, b.pos.x, y + 2.05f, b.pos.z);
        }
    }

    // Letters face the camera. Each '1' in the font is one tiny cube.
    private void drawName(String name, float x, float y, float z) {
        float advance = 0.48f;
        float start = -name.length() * advance * 0.5f;
        for (int i = 0; i < name.length(); i++) {
            String[] rows = FONT.get(name.charAt(i));
            if (rows == null) continue;
            for (int row = 0; row < rows.length; row++) {
                String line = rows[row];
                for (int col = 0; col < line.length(); col++) {
                    if (line.charAt(col) != '1') continue;
                    billboard(x, y, z,
                            start + i * advance + col * 0.075f,
                            (rows.length - 1 - row) * 0.075f,
                            0.95f, 0.86f, 0.4f);
                }
            }
        }
    }

    /** Draw a tiny cube that always faces the camera (for text). */
    private void billboard(float wx, float wy, float wz, float lx, float ly, float r, float g, float b) {
        model.identity()
                .translate(wx, wy, wz)
                .rotateY((float) -Math.toRadians(yaw + 90f))
                .translate(lx, ly, 0f)
                .scale(0.065f, 0.065f, 0.04f);
        uploadModel();
        glUniform3f(glGetUniformLocation(shaderProgram, "color"), r, g, b);
        glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0);
    }

    private void drawStars() {
        glBindVertexArray(vao);
        for (float[] star : stars) drawWorld(star[0], star[1], star[2], star[3], star[3], star[3], 0.92f, 0.94f, 0.97f);
    }

    private void drawPlanet() {
        glBindVertexArray(sphereVao);
        model.set(invShip).translate(PLANET_X, PLANET_Y, PLANET_Z).scale(12f);
        uploadModel();
        for (int i = 0; i < BANDS.length; i++) {
            glUniform3f(glGetUniformLocation(shaderProgram, "color"), BANDS[i][0], BANDS[i][1], BANDS[i][2]);
            glDrawArrays(GL_TRIANGLES, bandFirst[i], bandCount[i]);
        }
    }

    private float bearing(float x, float z) {
        return (float) Math.toDegrees(Math.atan2(x - shipPos.x, -(z - shipPos.z)));
    }

    private float distance(float x, float y, float z) {
        float dx = x - shipPos.x;
        float dy = y - shipPos.y;
        float dz = z - shipPos.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static float wrap(float deg) {
        while (deg > 180f) deg -= 360f;
        while (deg < -180f) deg += 360f;
        return deg;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Banded sphere used for the planet. */
    private void createSphere() {
        int stacks = BANDS.length;
        int slices = 10;
        ArrayList<Float> verts = new ArrayList<>();
        bandFirst = new int[stacks];
        bandCount = new int[stacks];
        for (int i = 0; i < stacks; i++) {
            bandFirst[i] = verts.size() / 3;
            double phi0 = Math.PI * i / stacks;
            double phi1 = Math.PI * (i + 1) / stacks;
            for (int j = 0; j < slices; j++) {
                double th0 = Math.PI * 2.0 * j / slices;
                double th1 = Math.PI * 2.0 * (j + 1) / slices;
                sphereVert(verts, phi0, th0);
                sphereVert(verts, phi0, th1);
                sphereVert(verts, phi1, th0);
                sphereVert(verts, phi0, th1);
                sphereVert(verts, phi1, th1);
                sphereVert(verts, phi1, th0);
            }
            bandCount[i] = verts.size() / 3 - bandFirst[i];
        }
        float[] data = new float[verts.size()];
        for (int i = 0; i < verts.size(); i++) data[i] = verts.get(i);
        sphereVao = glGenVertexArrays();
        sphereVbo = glGenBuffers();
        glBindVertexArray(sphereVao);
        FloatBuffer buf = BufferUtils.createFloatBuffer(data.length);
        buf.put(data).flip();
        glBindBuffer(GL_ARRAY_BUFFER, sphereVbo);
        glBufferData(GL_ARRAY_BUFFER, buf, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);
        glBindVertexArray(0);
    }

    private void sphereVert(ArrayList<Float> v, double phi, double theta) {
        v.add((float) (Math.sin(phi) * Math.cos(theta)));
        v.add((float) Math.cos(phi));
        v.add((float) (Math.sin(phi) * Math.sin(theta)));
    }

    /** Draw a cube in ship-local space. */
    private void draw(float x, float y, float z, float sx, float sy, float sz, float r, float g, float b) {
        model.identity().translate(x, y, z).scale(sx, sy, sz);
        uploadModel();
        glUniform3f(glGetUniformLocation(shaderProgram, "color"), r, g, b);
        glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0);
    }

    /** Draw a cube in world space (affected by ship orientation). */
    private void drawWorld(float x, float y, float z, float sx, float sy, float sz, float r, float g, float b) {
        model.set(invShip).translate(x, y, z).scale(sx, sy, sz);
        uploadModel();
        glUniform3f(glGetUniformLocation(shaderProgram, "color"), r, g, b);
        glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0);
    }

    private void uploadModel() {
        glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "model"), false, model.get(matBuf));
        glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "view"), false, view.get(matBuf));
        glUniformMatrix4fv(glGetUniformLocation(shaderProgram, "projection"), false, projection.get(matBuf));
    }

    private Vector3f getFront() {
        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);
        return new Vector3f(
                (float) (Math.cos(yawRad) * Math.cos(pitchRad)),
                (float) Math.sin(pitchRad),
                (float) (Math.sin(yawRad) * Math.cos(pitchRad))
        ).normalize();
    }

    /** Handle keyboard, mouse, jetpack, door, and ship flight. */
    private void processInput(float dt, double now) {
        boolean eDown = glfwGetKey(window, GLFW_KEY_E) == GLFW_PRESS;
        boolean fDown = glfwGetKey(window, GLFW_KEY_F) == GLFW_PRESS;
        // Edge-triggered E (door / jetpack)
        if (eDown && !eWas && !flying) {
            int rack = nearRack();
            if (rack >= 0) {
                if (jetRack == rack) {
                    jetRack = -1;
                    play(dropClip);
                } else if (jetRack < 0) {
                    jetRack = rack;
                    play(equipClip);
                }
            } else if (position.z > 2.3f && position.z < 4.4f) {
                doorOpen = !doorOpen;
                play(doorClip);
            }
        }
        // Edge-triggered F (enter / leave flight mode)
        if (fDown && !fWas) {
            if (flying) {
                flying = false;
                jetAudio(false);
            } else if (position.z < 1.2f && position.y < 2.2f) {
                flying = true;
                vy = 0f;
                jetAudio(false);
                position.set(0f, 1.6f, -0.2f);
                yaw = -90f;
                pitch = -4f;
            }
        }
        eWas = eDown;
        fWas = fDown;

        if (flying) {
            // Ship thrust and movement
            float accel = 0f;
            if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) accel += 14f;
            if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) accel -= 10f;
            shipSpeed += accel * dt;
            if (accel == 0f) shipSpeed *= (float) Math.exp(-0.8 * dt);
            shipSpeed = clamp(shipSpeed, -8f, 26f);
            float yr = (float) Math.toRadians(shipYaw);
            float pr = (float) Math.toRadians(shipPitch);
            float cp = (float) Math.cos(pr);
            shipPos.x += (float) Math.sin(yr) * cp * shipSpeed * dt;
            shipPos.y += (float) Math.sin(pr) * shipSpeed * dt;
            shipPos.z += (float) -Math.cos(yr) * cp * shipSpeed * dt;
        } else {
            // Walking
            Vector3f front = getFront();
            Vector3f right = new Vector3f(front).cross(0f, 1f, 0f).normalize();
            Vector3f move = new Vector3f();
            if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) move.add(front.x, 0f, front.z);
            if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) move.sub(front.x, 0f, front.z);
            if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) move.sub(right);
            if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) move.add(right);
            if (move.lengthSquared() > 0f) {
                move.normalize().mul(walkSpeed * dt);
                position.add(move);
            }
            // Jetpack / gravity
            boolean up = jetRack >= 0 && glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS;
            boolean drop = glfwGetKey(window, GLFW_KEY_LEFT_SHIFT) == GLFW_PRESS;
            if (up) vy += 24f * dt;
            else vy -= 20f * dt;
            if (drop) vy -= 18f * dt;
            vy = clamp(vy, -16f, 10f);
            position.y += vy * dt;
            jetAudio(up);
            pushBots();
            keepInside();
        }

        if (glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS) glfwSetWindowShouldClose(window, true);
    }

    private int nearRack() {
        if (position.y > 3.2f) return -1;
        for (int i = 0; i < racks.length; i++) {
            float dx = position.x - racks[i][0];
            float dz = position.z - racks[i][1];
            if (dx * dx + dz * dz < 2.6f) return i;
        }
        return -1;
    }

    /** Soft collision so the player does not walk through robots. */
    private void pushBots() {
        for (Bot b : bots) {
            float dx = position.x - b.pos.x;
            float dz = position.z - b.pos.z;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d > 0.85f || d < 0.001f) continue;
            if (Math.abs(position.y - (b.pos.y + 1.1f)) > 1.5f) continue;
            position.x = b.pos.x + dx / d * 0.85f;
            position.z = b.pos.z + dz / d * 0.85f;
        }
    }

    /** Keep the player inside the defined rooms and on solid floors. */
    private void keepInside() {
        // Cockpit special case (door)
        if (position.z < 3.35f) {
            position.x = clamp(position.x, -1.55f, 1.55f);
            if (position.z < -1.15f) position.z = -1.15f;
            if (doorAnim < 0.65f && position.z > 3.25f) position.z = 3.25f;
            vertical(2.25f);
            lastSafe.set(position);
            return;
        }
        // Door threshold
        if (position.z < 4.3f) {
            if (doorAnim < 0.65f) {
                if (lastSafe.z < 3.4f) position.set(lastSafe);
                else position.z = 4.3f;
            } else {
                position.x = clamp(position.x, -0.42f, 0.42f);
            }
            vertical(2.25f);
            if (doorAnim >= 0.65f || lastSafe.z >= 3.4f) lastSafe.set(position);
            return;
        }
        // General room bounds
        if (!inSomeRoom(position.x, position.z)) {
            position.x = lastSafe.x;
            position.z = lastSafe.z;
        }
        pushPillars();
        if (!inSomeRoom(position.x, position.z)) {
            position.x = lastSafe.x;
            position.z = lastSafe.z;
        }
        vertical(ceilAt(position.x, position.z));
        lastSafe.set(position);
    }

    /** Push the player out of pillar volumes. */
    private void pushPillars() {
        for (float[] p : pillars) {
            float dx = position.x - p[0];
            float dz = position.z - p[1];
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d < p[2] && d > 0.001f) {
                position.x = p[0] + dx / d * p[2];
                position.z = p[1] + dz / d * p[2];
            }
        }
    }

    private boolean inSomeRoom(float x, float z) {
        for (Room r : rooms) if (r.has(x, z)) return true;
        return false;
    }

    private float ceilAt(float x, float z) {
        float ceil = 2.3f;
        boolean any = false;
        for (Room r : rooms) {
            if (!r.has(x, z)) continue;
            ceil = any ? Math.min(ceil, r.ceil) : r.ceil;
            any = true;
        }
        return ceil;
    }

    /** Clamp vertical position to floor and ceiling. */
    private void vertical(float ceil) {
        float floor = 1.6f;
        for (Pad p : pads) {
            if (p.has(position.x, position.z) && position.y >= p.eye - 0.45f) floor = Math.max(floor, p.eye);
        }
        if (position.y < floor) {
            position.y = floor;
            if (vy < 0f) vy = 0f;
        }
        if (position.y > ceil) {
            position.y = ceil;
            if (vy > 0f) vy = 0f;
        }
    }

    private void initAudio() {
        jetClip = loadNoise(0.45f, 9000f);
        doorClip = loadSweep(0.18f, 520f, 90f);
        equipClip = loadBeep(0.12f, 660f);
        dropClip = loadBeep(0.1f, 220f);
    }

    /** Start or stop the continuous jetpack noise. */
    private void jetAudio(boolean on) {
        if (jetClip == null) return;
        if (on && !jetOn) {
            jetClip.loop(Clip.LOOP_CONTINUOUSLY);
            jetOn = true;
        } else if (!on && jetOn) {
            jetClip.stop();
            jetClip.setFramePosition(0);
            jetOn = false;
        }
    }

    private void play(Clip clip) {
        if (clip == null) return;
        if (clip.isRunning()) clip.stop();
        clip.setFramePosition(0);
        clip.start();
    }

    // 16-bit mono little-endian. JavaSound plays it. No extra libraries.
    private Clip openClip(byte[] data, int sr) {
        try {
            AudioFormat fmt = new AudioFormat(sr, 16, 1, true, false);
            Clip clip = AudioSystem.getClip();
            clip.open(fmt, data, 0, data.length);
            return clip;
        } catch (Exception e) {
            System.err.println("Audio off: " + e.getMessage());
            return null;
        }
    }

    private void writeSample(byte[] data, int i, int v) {
        if (v > 32767) v = 32767;
        if (v < -32767) v = -32767;
        data[i * 2] = (byte) (v & 0xff);
        data[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
    }

    private Clip loadNoise(float seconds, float volume) {
        int sr = 22050;
        int n = (int) (sr * seconds);
        byte[] data = new byte[n * 2];
        float acc = 0f;
        for (int i = 0; i < n; i++) {
            float white = (float) Math.random() * 2f - 1f;
            acc = acc * 0.8f + white * 0.2f;
            writeSample(data, i, (int) (acc * volume));
        }
        return openClip(data, sr);
    }

    private Clip loadBeep(float seconds, float freq) {
        int sr = 22050;
        int n = (int) (sr * seconds);
        byte[] data = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            float t = i / (float) sr;
            float env = 1f - t / seconds;
            float s = (float) Math.sin(2 * Math.PI * freq * t) * env;
            writeSample(data, i, (int) (s * 14000));
        }
        return openClip(data, sr);
    }

    private Clip loadSweep(float seconds, float f0, float f1) {
        int sr = 22050;
        int n = (int) (sr * seconds);
        byte[] data = new byte[n * 2];
        float phase = 0f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) sr;
            float f = f0 + (f1 - f0) * (t / seconds);
            phase += (float) (2 * Math.PI * f / sr);
            float env = 1f - t / seconds;
            writeSample(data, i, (int) (Math.sin(phase) * env * 12000));
        }
        return openClip(data, sr);
    }

    /** Minimal flat-color shader. */
    private int createShaderProgram() {
        String vertexSrc = """
                #version 330 core
                layout (location = 0) in vec3 aPos;
                uniform mat4 model;
                uniform mat4 view;
                uniform mat4 projection;
                void main() {
                    gl_Position = projection * view * model * vec4(aPos, 1.0);
                }
                """;
        String fragmentSrc = """
                #version 330 core
                out vec4 FragColor;
                uniform vec3 color;
                void main() {
                    FragColor = vec4(color, 1.0);
                }
                """;
        int vs = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vs, vertexSrc);
        glCompileShader(vs);
        if (glGetShaderi(vs, GL_COMPILE_STATUS) == GL_FALSE) throw new RuntimeException(glGetShaderInfoLog(vs));
        int fs = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fs, fragmentSrc);
        glCompileShader(fs);
        if (glGetShaderi(fs, GL_COMPILE_STATUS) == GL_FALSE) throw new RuntimeException(glGetShaderInfoLog(fs));
        int prog = glCreateProgram();
        glAttachShader(prog, vs);
        glAttachShader(prog, fs);
        glLinkProgram(prog);
        if (glGetProgrami(prog, GL_LINK_STATUS) == GL_FALSE) throw new RuntimeException(glGetProgramInfoLog(prog));
        glDeleteShader(vs);
        glDeleteShader(fs);
        return prog;
    }

    /** Unit cube centered at origin, used for almost everything. */
    private void createCube() {
        float[] vertices = {
                -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f,
                -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, -0.5f, 0.5f, -0.5f,
                -0.5f, -0.5f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, -0.5f,
                0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, -0.5f,
                -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f,
                -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f
        };
        int[] indices = {
                0, 1, 2, 0, 2, 3,
                4, 5, 6, 4, 6, 7,
                8, 9, 10, 8, 10, 11,
                12, 13, 14, 12, 14, 15,
                16, 17, 18, 16, 18, 19,
                20, 21, 22, 20, 22, 23
        };
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        ebo = glGenBuffers();
        glBindVertexArray(vao);
        FloatBuffer vbuf = BufferUtils.createFloatBuffer(vertices.length);
        vbuf.put(vertices).flip();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vbuf, GL_STATIC_DRAW);
        IntBuffer ibuf = BufferUtils.createIntBuffer(indices.length);
        ibuf.put(indices).flip();
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, ibuf, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);
        glBindVertexArray(0);
    }

    private void cleanup() {
        if (jetClip != null) jetClip.close();
        if (doorClip != null) doorClip.close();
        if (equipClip != null) equipClip.close();
        if (dropClip != null) dropClip.close();
        for (Clip c : chirps) if (c != null) c.close();
        glDeleteVertexArrays(vao);
        glDeleteVertexArrays(sphereVao);
        glDeleteBuffers(vbo);
        glDeleteBuffers(sphereVbo);
        glDeleteBuffers(ebo);
        glDeleteProgram(shaderProgram);
        if (fbo != 0) {
            glDeleteFramebuffers(fbo);
            glDeleteTextures(colorTex);
            glDeleteRenderbuffers(depthRb);
        }
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    /** A box you are allowed to walk in. ceil is the highest your eyes can go. */
    private static final class Room {
        final float x0, x1, z0, z1, ceil;

        Room(float x0, float x1, float z0, float z1, float ceil) {
            this.x0 = x0;
            this.x1 = x1;
            this.z0 = z0;
            this.z1 = z1;
            this.ceil = ceil;
        }

        boolean has(float x, float z) {
            return x >= x0 && x <= x1 && z >= z0 && z <= z1;
        }
    }

    /** A high deck. eye is your eye height when you are standing on it. */
    private static final class Pad {
        final float x0, x1, z0, z1, eye;

        Pad(float x0, float x1, float z0, float z1, float eye) {
            this.x0 = x0;
            this.x1 = x1;
            this.z0 = z0;
            this.z1 = z1;
            this.eye = eye;
        }

        boolean has(float x, float z) {
            return x >= x0 && x <= x1 && z >= z0 && z <= z1;
        }
    }

    /** A named robot. hover = 0 means it walks. Otherwise it floats at that height. */
    private static final class Bot {
        final String name;
        final float r, g, b;
        final float hover;
        final float chirp;
        final float[][] path;
        final Vector3f pos = new Vector3f();
        int wp = 1;
        float yaw;
        float nextChirp;

        Bot(String name, float r, float g, float b, float hover, float chirp, float[][] path) {
            this.name = name;
            this.r = r;
            this.g = g;
            this.b = b;
            this.hover = hover;
            this.chirp = chirp;
            this.path = path;
            pos.set(path[0][0], hover, path[0][1]);
        }
    }
}
