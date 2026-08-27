package inkball;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import processing.core.PApplet;
import processing.core.PImage;
import processing.core.PVector;
import processing.data.JSONArray;
import processing.data.JSONObject;
import processing.event.KeyEvent;
import processing.event.MouseEvent;

/**
 * The Processing application for InkBall. It loads configured levels, updates
 * entities once per frame, and renders the board and top bar.
 */
public class App extends PApplet {
    public static final int CELLSIZE = 32;
    public static final int TOPBAR = 64;
    public static final int WIDTH = 576;
    public static final int HEIGHT = 640;
    public static final int BOARD_WIDTH = WIDTH / CELLSIZE;
    public static final int BOARD_HEIGHT = (HEIGHT - TOPBAR) / CELLSIZE;
    public static final int FPS = 30;

    private static final int COMPLETION_TICK_MILLIS = 67;
    private static final float HOLE_RANGE = CELLSIZE;
    private static final float HOLE_CAPTURE_DISTANCE = 6.0f;
    private static final int QUEUE_START_X = 8;
    private static final int QUEUE_GAP = 30;
    private static final int SPAWN_TIMER_X = 190;
    private static final int STATUS_CENTRE_X = 315;

    public static final Random random = new Random();
    public String configPath = "config.json";
    public String img = "src/main/resources/inkball/";
    public final Map<String, PImage> images = new HashMap<String, PImage>();
    public final ArrayList<Ball> balls = new ArrayList<Ball>();
    public final char[][] tiles = new char[BOARD_HEIGHT][BOARD_WIDTH];

    private final List<PVector> linePoints = new ArrayList<PVector>();
    private final List<List<PVector>> lines = new ArrayList<List<PVector>>();
    private final List<Hole> holes = new ArrayList<Hole>();

    protected float[] savedVx = new float[0];
    protected float[] savedVy = new float[0];
    private JSONObject config;
    private JSONObject scoreIncreaseData;
    private JSONObject scoreDecreaseData;
    private List<String> remainingBalls = new ArrayList<String>();
    private int currentLevelIndex;
    private int score;
    private int scoreAtLevelStart;
    private int timer;
    private int remainingTime;
    private boolean hasTimer;
    private int levelStartTime;
    private int pauseStartedAt;
    private int spawnInterval;
    private float spawnTimer;
    private float queueSlide;
    private double scoreIncreaseModifier;
    private double scoreDecreaseModifier;
    private boolean paused;
    private boolean timeUp;
    private boolean levelCompleting;
    private boolean gameEnded;
    private boolean autoSolveEnabled;
    private int completionLastTick;
    private int yellowStep;

    private static final class Hole {
        private final float centreX;
        private final float centreY;
        private final String colour;
        private final int col;
        private final int row;

        private Hole(int col, int row, String colour) {
            this.col = col;
            this.row = row;
            this.colour = colour;
            centreX = col * CELLSIZE + CELLSIZE;
            centreY = row * CELLSIZE + TOPBAR + CELLSIZE;
        }
    }

    @Override
    public void settings() {
        size(WIDTH, HEIGHT);
    }

    @Override
    public void setup() {
        frameRate(FPS);
        loadGameImages();
        config = loadJSONObject(configPath);
        if (config == null) throw new IllegalStateException("Could not load " + configPath);
        score = 0;
        currentLevelIndex = 0;
        gameEnded = false;
        startLevel(currentLevelIndex);
    }

    public int getTimer() { return timer; }
    public int getScore() { return score; }
    public List<String> getRemainingBalls() { return new ArrayList<String>(remainingBalls); }
    public boolean getpaused() { return paused; }

    /** Intended for lightweight state tests; normal play should use the spacebar. */
    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    /** Loads the configuration settings for one level, without creating map entities. */
    public void loadLevelData(int levelIndex) {
        JSONObject level = getLevel(levelIndex);
        timer = readNonNegativeInt(level, "time", -1);
        hasTimer = timer >= 0;
        remainingTime = hasTimer ? timer : 0;
        spawnInterval = Math.max(0, readNonNegativeInt(level, "spawn_interval", 0));
        spawnTimer = spawnInterval;
        scoreIncreaseModifier = readDouble(level, "score_increase_from_hole_capture_modifier", 1.0);
        scoreDecreaseModifier = readDouble(level, "score_decrease_from_wrong_hole_modifier", 1.0);
        scoreIncreaseData = objectFrom(level, "score_increase_from_hole_capture",
                objectFrom(config, "score_increase_from_hole_capture", null));
        scoreDecreaseData = objectFrom(level, "score_decrease_from_wrong_hole",
                objectFrom(config, "score_decrease_from_wrong_hole", null));

        remainingBalls = new ArrayList<String>();
        try {
            JSONArray configuredBalls = level.getJSONArray("balls");
            for (int index = 0; index < configuredBalls.size(); index++) {
                remainingBalls.add(configuredBalls.getString(index));
            }
        } catch (RuntimeException ignored) {
            // A level may have no configured spawns; initial B# map entities still work.
        }
    }

    private JSONObject getLevel(int index) {
        if (config == null) throw new IllegalStateException("Game configuration has not been loaded");
        JSONArray levels = config.getJSONArray("levels");
        if (index < 0 || index >= levels.size()) throw new IllegalArgumentException("Unknown level " + index);
        return levels.getJSONObject(index);
    }

    private void startLevel(int levelIndex) {
        JSONObject level = getLevel(levelIndex);
        balls.clear();
        lines.clear();
        linePoints.clear();
        holes.clear();
        loadLevel(level.getString("layout"));
        loadLevelData(levelIndex);
        scoreAtLevelStart = score;
        levelStartTime = millis();
        pauseStartedAt = 0;
        queueSlide = 0;
        paused = false;
        timeUp = false;
        levelCompleting = false;
        yellowStep = 0;
    }

    private int readNonNegativeInt(JSONObject object, String key, int fallback) {
        try {
            Object rawValue = object.get(key);
            if (!(rawValue instanceof Number)) return fallback;
            double number = ((Number) rawValue).doubleValue();
            if (number < 0 || number > Integer.MAX_VALUE || number != Math.rint(number)) return fallback;
            return (int) number;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private double readDouble(JSONObject object, String key, double fallback) {
        try {
            return object.getDouble(key);
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private JSONObject objectFrom(JSONObject object, String key, JSONObject fallback) {
        try {
            JSONObject value = object.getJSONObject(key);
            return value == null ? fallback : value;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private void loadGameImages() {
        images.clear();
        images.put(" ", loadImageFromPath("tile.png"));
        images.put("X", loadImageFromPath("wall0.png"));
        images.put("S", loadImageFromPath("entrypoint.png"));
        for (int colour = 0; colour <= 4; colour++) {
            images.put("B" + colour, loadImageFromPath("ball" + colour + ".png"));
            images.put("H" + colour, loadImageFromPath("hole" + colour + ".png"));
            if (colour > 0) images.put(String.valueOf(colour), loadImageFromPath("wall" + colour + ".png"));
        }
        images.put("grey", images.get("B0"));
        images.put("orange", images.get("B1"));
        images.put("blue", images.get("B2"));
        images.put("green", images.get("B3"));
        images.put("yellow", images.get("B4"));
    }

    /** Loads an image from the source tree while retaining support for packaged resources. */
    public PImage loadImageFromPath(String filename) {
        PImage image = loadImage(img + filename);
        return image != null ? image : loadImage("inkball/" + filename);
    }

    /**
     * Reads an 18 by 18 (or smaller) map. Omitted cells remain empty, which
     * allows balls to bounce against the board edge as required.
     */
    protected void loadLevel(String levelFile) {
        for (char[] row : tiles) Arrays.fill(row, ' ');
        holes.clear();
        try (BufferedReader reader = createReader(levelFile)) {
            String line;
            int row = 0;
            while ((line = reader.readLine()) != null && row < BOARD_HEIGHT) {
                for (int col = 0; col < line.length() && col < BOARD_WIDTH; col++) {
                    char tile = line.charAt(col);
                    if (tile == 'B' && col + 1 < line.length() && isColourCode(line.charAt(col + 1))) {
                        String type = "B" + line.charAt(col + 1);
                        PImage ballImage = imageForBall(type);
                        if (ballImage != null) {
                            balls.add(new Ball(col * CELLSIZE, row * CELLSIZE + TOPBAR, 2, 2, type, ballImage));
                        }
                        col++;
                    } else {
                        tiles[row][col] = tile;
                    }
                }
                row++;
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Could not load level " + levelFile, exception);
        }
        collectHoles();
    }

    private void collectHoles() {
        for (int row = 0; row < BOARD_HEIGHT; row++) {
            for (int col = 0; col + 1 < BOARD_WIDTH; col++) {
                if (tiles[row][col] == 'H' && isColourCode(tiles[row][col + 1])) {
                    holes.add(new Hole(col, row, getHoleColor("H" + tiles[row][col + 1])));
                }
            }
        }
    }

    private boolean isColourCode(char value) {
        return value >= '0' && value <= '4';
    }

    @Override
    public void keyPressed(KeyEvent event) {
        handleKey(event.getKey());
    }

    @Override
    public void keyPressed() {
        handleKey(key);
    }

    private void handleKey(char pressedKey) {
        if (pressedKey == 'r' || pressedKey == 'R') {
            if (gameEnded) {
                score = 0;
                currentLevelIndex = 0;
                gameEnded = false;
            } else {
                score = scoreAtLevelStart;
            }
            startLevel(currentLevelIndex);
        } else if (pressedKey == 'c' || pressedKey == 'C') {
            autoSolveEnabled = !autoSolveEnabled;
        } else if (pressedKey == ' ' && !timeUp && !gameEnded && !levelCompleting) {
            if (paused) resumeGame(); else pauseGame();
        }
    }

    protected void pauseGame() {
        if (paused) return;
        paused = true;
        pauseStartedAt = millis();
        savedVx = new float[balls.size()];
        savedVy = new float[balls.size()];
        for (int index = 0; index < balls.size(); index++) {
            savedVx[index] = balls.get(index).getVx();
            savedVy[index] = balls.get(index).getVy();
        }
    }

    private void resumeGame() {
        paused = false;
        levelStartTime += millis() - pauseStartedAt;
    }

    @Override
    public void mousePressed(MouseEvent event) {
        if (!canDraw()) return;
        if (mouseButton == LEFT && inBoard(mouseX, mouseY)) {
            linePoints.clear();
            linePoints.add(new PVector(mouseX, mouseY));
        } else if (mouseButton == RIGHT) {
            lines.clear();
            linePoints.clear();
        }
    }

    @Override
    public void mouseDragged(MouseEvent event) {
        if (!canDraw() || mouseButton != LEFT || !inBoard(mouseX, mouseY)) return;
        PVector point = new PVector(mouseX, mouseY);
        if (linePoints.isEmpty() || point.dist(linePoints.get(linePoints.size() - 1)) >= 2.0f) linePoints.add(point);
    }

    @Override
    public void mouseReleased(MouseEvent event) {
        if (canDraw() && mouseButton == LEFT && linePoints.size() > 1) {
            lines.add(new ArrayList<PVector>(linePoints));
        }
        linePoints.clear();
    }

    private boolean canDraw() {
        return !timeUp && !gameEnded && !levelCompleting;
    }

    private boolean inBoard(float x, float y) {
        return x >= 0 && x < WIDTH && y >= TOPBAR && y < HEIGHT;
    }

    private float distanceToSegment(PVector point, PVector first, PVector second) {
        PVector segment = PVector.sub(second, first);
        float lengthSquared = segment.magSq();
        if (lengthSquared == 0) return point.dist(first);
        float t = Math.max(0, Math.min(1, PVector.sub(point, first).dot(segment) / lengthSquared));
        return point.dist(PVector.add(first, PVector.mult(segment, t)));
    }

    @Override
    public void draw() {
        background(200);
        drawBoard();
        if (levelCompleting) updateLevelCompletion();
        else if (!paused && !timeUp && !gameEnded) updatePlayingState();
        drawBallsAndLines();
        if (levelCompleting) drawCompletionTiles();
        drawTopBar();
    }

    private void updatePlayingState() {
        if (hasTimer) {
            remainingTime = Math.max(0, timer - (millis() - levelStartTime) / 1000);
            if (remainingTime == 0) {
                timeUp = true;
                linePoints.clear();
                return;
            }
        }

        if (queueSlide > 0) queueSlide = Math.max(0, queueSlide - 1);
        for (Ball ball : balls) {
            if (autoSolveEnabled) {
                Hole targetHole = nearestCompatibleHole(ball);
                if (targetHole != null) {
                    if (distanceToHole(ball, targetHole) <= HOLE_CAPTURE_DISTANCE) {
                        captureBall(ball, targetHole);
                        continue;
                    }
                    float previousCentreX = ball.getCentreX();
                    float previousCentreY = ball.getCentreY();
                    ball.guideTowards(targetHole.centreX, targetHole.centreY);
                    if (distanceToHole(ball, targetHole) <= HOLE_CAPTURE_DISTANCE
                            || movementCrossedHole(previousCentreX, previousCentreY, ball, targetHole)) {
                        captureBall(ball, targetHole);
                    }
                    continue;
                }
            }

            Hole nearbyHole = nearestHoleInRange(ball);
            if (nearbyHole != null && distanceToHole(ball, nearbyHole) <= HOLE_CAPTURE_DISTANCE) {
                captureBall(ball, nearbyHole);
                continue;
            }

            if (nearbyHole == null) {
                ball.clearAttraction();
            } else {
                ball.attractTowards(nearbyHole.centreX, nearbyHole.centreY);
            }

            float previousCentreX = ball.getCentreX();
            float previousCentreY = ball.getCentreY();
            ball.update(tiles, images, BOARD_WIDTH, BOARD_HEIGHT, lines);
            Hole captureHole = nearestHoleInRange(ball);
            if (captureHole == null) captureHole = nearbyHole;
            if (captureHole != null && (distanceToHole(ball, captureHole) <= HOLE_CAPTURE_DISTANCE
                    || movementCrossedHole(previousCentreX, previousCentreY, ball, captureHole))) {
                captureBall(ball, captureHole);
            }
        }
        balls.removeIf(Ball::isRemoved);
        lines.removeIf(line -> line.size() < 2);

        if (!remainingBalls.isEmpty()) {
            spawnTimer -= 1.0f / FPS;
            if (spawnTimer <= 0) {
                String ballType = remainingBalls.remove(0);
                spawnNextBallAtS(ballType);
                spawnTimer = spawnInterval;
                queueSlide = QUEUE_GAP;
            }
        }

        if (remainingBalls.isEmpty() && balls.isEmpty()) beginLevelCompletion();
    }

    private Hole nearestHoleInRange(Ball ball) {
        Hole nearest = null;
        float nearestDistance = Float.MAX_VALUE;
        for (Hole hole : holes) {
            float distance = distanceToHole(ball, hole);
            if (distance <= HOLE_RANGE && distance < nearestDistance) {
                nearest = hole;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    /** Chooses the nearest hole which counts as a successful capture. */
    private Hole nearestCompatibleHole(Ball ball) {
        String ballColour = colourForBall(ball.getType());
        Hole exactMatch = null;
        Hole greyFallback = null;
        float exactDistance = Float.MAX_VALUE;
        float greyDistance = Float.MAX_VALUE;
        for (Hole hole : holes) {
            float distance = distanceToHole(ball, hole);
            if ("grey".equals(ballColour)) {
                if (distance < exactDistance) {
                    exactMatch = hole;
                    exactDistance = distance;
                }
            } else if (ballColour.equals(hole.colour) && distance < exactDistance) {
                exactMatch = hole;
                exactDistance = distance;
            } else if ("grey".equals(hole.colour) && distance < greyDistance) {
                greyFallback = hole;
                greyDistance = distance;
            }
        }
        return exactMatch != null ? exactMatch : greyFallback;
    }

    private float distanceToHole(Ball ball, Hole hole) {
        float dx = ball.getCentreX() - hole.centreX;
        float dy = ball.getCentreY() - hole.centreY;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** Prevents a ball moving several pixels per frame from tunnelling through a hole centre. */
    private boolean movementCrossedHole(float previousX, float previousY, Ball ball, Hole hole) {
        PVector holeCentre = new PVector(hole.centreX, hole.centreY);
        PVector previousCentre = new PVector(previousX, previousY);
        PVector currentCentre = new PVector(ball.getCentreX(), ball.getCentreY());
        return distanceToSegment(holeCentre, previousCentre, currentCentre) <= HOLE_CAPTURE_DISTANCE;
    }

    private void captureBall(Ball ball, Hole hole) {
        if (ball.isRemoved()) return;
        String ballColour = colourForBall(ball.getType());
        boolean successfulCapture = ballColour.equals(hole.colour)
                || "grey".equals(ballColour) || "grey".equals(hole.colour);
        if (successfulCapture) {
            score += scaledScore(scoreIncreaseData, ballColour, scoreIncreaseModifier);
        } else {
            score -= scaledScore(scoreDecreaseData, ballColour, scoreDecreaseModifier);
            remainingBalls.add(ballColour);
        }
        ball.setRemoved(true);
    }

    private int scaledScore(JSONObject scoreData, String colour, double modifier) {
        if (scoreData == null) return 0;
        try {
            return (int) Math.round(scoreData.getInt(colour) * modifier);
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    /** Compatibility entry point used by tests and non-rendering game loops. */
    public void checkBallHoleCollision(Ball ball) {
        Hole hole = nearestHoleInRange(ball);
        if (hole != null && distanceToHole(ball, hole) <= HOLE_CAPTURE_DISTANCE) captureBall(ball, hole);
    }

    private void beginLevelCompletion() {
        levelCompleting = true;
        completionLastTick = millis();
        yellowStep = 0;
    }

    private void updateLevelCompletion() {
        if (!hasTimer || remainingTime <= 0) {
            finishLevel();
            return;
        }
        while (millis() - completionLastTick >= COMPLETION_TICK_MILLIS) {
            score++;
            remainingTime--;
            yellowStep++;
            completionLastTick += COMPLETION_TICK_MILLIS;
            if (remainingTime == 0) {
                finishLevel();
                return;
            }
        }
    }

    private void finishLevel() {
        levelCompleting = false;
        currentLevelIndex++;
        if (currentLevelIndex >= config.getJSONArray("levels").size()) {
            gameEnded = true;
        } else {
            startLevel(currentLevelIndex);
        }
    }

    private void drawBoard() {
        PImage emptyTile = images.get(" ");
        for (int row = 0; row < BOARD_HEIGHT; row++) {
            for (int col = 0; col < BOARD_WIDTH; col++) {
                if (emptyTile != null) image(emptyTile, col * CELLSIZE, row * CELLSIZE + TOPBAR, CELLSIZE, CELLSIZE);
            }
        }
        for (int row = 0; row < BOARD_HEIGHT; row++) {
            for (int col = 0; col < BOARD_WIDTH; col++) {
                char tile = tiles[row][col];
                if (tile == ' ' || tile == 'H' || isHoleColourMarker(row, col)) continue;
                PImage tileImage = images.get(String.valueOf(tile));
                if (tileImage != null) image(tileImage, col * CELLSIZE, row * CELLSIZE + TOPBAR, CELLSIZE, CELLSIZE);
            }
        }
        for (Hole hole : holes) {
            PImage holeImage = images.get("H" + colourCode(hole.colour));
            if (holeImage != null) image(holeImage, hole.col * CELLSIZE, hole.row * CELLSIZE + TOPBAR,
                    CELLSIZE * 2, CELLSIZE * 2);
        }
    }

    private boolean isHoleColourMarker(int row, int col) {
        return col > 0 && tiles[row][col - 1] == 'H' && isColourCode(tiles[row][col]);
    }

    private void drawBallsAndLines() {
        for (Ball ball : balls) ball.draw(this);
        stroke(0);
        strokeWeight(10);
        for (List<PVector> line : lines) drawLine(line);
        if (canDraw()) drawLine(linePoints);
    }

    private void drawLine(List<PVector> points) {
        for (int index = 0; index + 1 < points.size(); index++) {
            PVector first = points.get(index);
            PVector second = points.get(index + 1);
            line(first.x, first.y, second.x, second.y);
        }
    }

    private void drawCompletionTiles() {
        List<PVector> perimeter = boardPerimeter();
        if (perimeter.isEmpty()) return;
        PImage yellowWall = images.get("4");
        if (yellowWall == null) return;
        PVector first = perimeter.get(yellowStep % perimeter.size());
        PVector second = perimeter.get((yellowStep + perimeter.size() / 2) % perimeter.size());
        image(yellowWall, first.x * CELLSIZE, first.y * CELLSIZE + TOPBAR, CELLSIZE, CELLSIZE);
        image(yellowWall, second.x * CELLSIZE, second.y * CELLSIZE + TOPBAR, CELLSIZE, CELLSIZE);
    }

    private List<PVector> boardPerimeter() {
        List<PVector> perimeter = new ArrayList<PVector>();
        for (int col = 0; col < BOARD_WIDTH; col++) perimeter.add(new PVector(col, 0));
        for (int row = 1; row < BOARD_HEIGHT; row++) perimeter.add(new PVector(BOARD_WIDTH - 1, row));
        for (int col = BOARD_WIDTH - 2; col >= 0; col--) perimeter.add(new PVector(col, BOARD_HEIGHT - 1));
        for (int row = BOARD_HEIGHT - 2; row > 0; row--) perimeter.add(new PVector(0, row));
        return perimeter;
    }

    private void drawTopBar() {
        noStroke();
        fill(0);
        rect(0, 0, WIDTH, TOPBAR);
        fill(255);
        textSize(16);
        textAlign(RIGHT, CENTER);
        text("Score: " + score, WIDTH - 10, 17);
        text("Time: " + (hasTimer ? remainingTime : "--"), WIDTH - 10, 47);

        if (!remainingBalls.isEmpty() && !timeUp && !gameEnded) {
            textAlign(LEFT, CENTER);
            text(String.format(Locale.ROOT, "%.1f", Math.max(0, spawnTimer)), SPAWN_TIMER_X,
                    TOPBAR / 2.0f);
            for (int index = 0; index < Math.min(5, remainingBalls.size()); index++) {
                PImage ballImage = imageForBall(remainingBalls.get(index));
                if (ballImage != null) {
                    image(ballImage, QUEUE_START_X + index * QUEUE_GAP + queueSlide, TOPBAR / 2.0f - ballImage.height / 2.0f);
                }
            }
        }

        textAlign(CENTER, CENTER);
        textSize(20);
        if (timeUp || gameEnded || paused) {
            fill(0);
            rect(185, 4, 245, TOPBAR - 8);
            fill(255);
            if (timeUp) text("=== TIME'S UP ===", STATUS_CENTRE_X, TOPBAR / 2.0f);
            else if (gameEnded) text("=== ENDED ===", STATUS_CENTRE_X, TOPBAR / 2.0f);
            else text("*** PAUSED ***", STATUS_CENTRE_X, TOPBAR / 2.0f);
        }
    }

    protected void spawnNextBallAtS(String ballType) {
        List<PVector> spawners = findSpawnPoints();
        PImage ballImage = imageForBall(ballType);
        if (spawners.isEmpty() || ballImage == null) return;
        PVector spawnPoint = spawners.get(random.nextInt(spawners.size()));
        float vx = random.nextBoolean() ? -2 : 2;
        float vy = random.nextBoolean() ? -2 : 2;
        balls.add(new Ball(spawnPoint.x, spawnPoint.y, vx, vy, ballType, ballImage));
    }

    private List<PVector> findSpawnPoints() {
        List<PVector> spawners = new ArrayList<PVector>();
        for (int row = 0; row < BOARD_HEIGHT; row++) {
            for (int col = 0; col < BOARD_WIDTH; col++) {
                if (tiles[row][col] == 'S') spawners.add(new PVector(col * CELLSIZE, row * CELLSIZE + TOPBAR));
            }
        }
        return spawners;
    }

    protected PVector findSpawnPoint(char spawnChar) {
        for (int row = 0; row < BOARD_HEIGHT; row++) {
            for (int col = 0; col < BOARD_WIDTH; col++) {
                if (tiles[row][col] == spawnChar) return new PVector(col * CELLSIZE, row * CELLSIZE + TOPBAR);
            }
        }
        return null;
    }

    protected String getHoleColor(String holeTile) {
        if (holeTile == null || holeTile.length() != 2 || holeTile.charAt(0) != 'H') return "";
        switch (holeTile.charAt(1)) {
            case '0': return "grey";
            case '1': return "orange";
            case '2': return "blue";
            case '3': return "green";
            case '4': return "yellow";
            default: return "";
        }
    }

    private PImage imageForBall(String ballType) {
        if (ballType == null) return null;
        PImage direct = images.get(ballType);
        return direct != null ? direct : images.get("B" + colourCode(colourForBall(ballType)));
    }

    private String colourForBall(String ballType) {
        String normalised = ballType == null ? "grey" : ballType.toLowerCase(Locale.ROOT);
        if (normalised.length() == 2 && normalised.charAt(0) == 'b' && isColourCode(normalised.charAt(1))) {
            return getHoleColor("H" + normalised.charAt(1));
        }
        return "orange".equals(normalised) || "blue".equals(normalised) || "green".equals(normalised)
                || "yellow".equals(normalised) ? normalised : "grey";
    }

    private char colourCode(String colour) {
        if ("orange".equals(colour)) return '1';
        if ("blue".equals(colour)) return '2';
        if ("green".equals(colour)) return '3';
        if ("yellow".equals(colour)) return '4';
        return '0';
    }

    public static void main(String[] args) {
        PApplet.main("inkball.App");
    }
}
