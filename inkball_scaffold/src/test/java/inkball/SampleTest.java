package inkball;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.FileReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import processing.core.PImage;
import processing.core.PVector;
import processing.data.JSONObject;

/** Unit tests for physics and non-rendering game state. */
public class SampleTest {
    private char[][] emptyBoard() {
        char[][] board = new char[App.BOARD_HEIGHT][App.BOARD_WIDTH];
        for (char[] row : board) Arrays.fill(row, ' ');
        return board;
    }

    private Map<String, PImage> ballImages() {
        Map<String, PImage> images = new HashMap<String, PImage>();
        for (int colour = 0; colour <= 4; colour++) images.put("B" + colour, new PImage());
        return images;
    }

    @Test
    public void ballRetainsItsInitialState() {
        // A fresh ball exposes its position and velocity without alteration.
        Ball ball = new Ball(100, 120, 2, -2, "B0", new PImage());
        assertEquals(100, ball.getX());
        assertEquals(120, ball.getY());
        assertEquals(2, ball.getVx());
        assertEquals(-2, ball.getVy());
        assertFalse(ball.isRemoved());
    }

    @Test
    public void colouredWallReflectsAndChangesBallColour() {
        // A ball entering a coloured wall reverses and adopts that wall's sprite and type.
        char[][] board = emptyBoard();
        board[1][2] = '1';
        Map<String, PImage> images = ballImages();
        Ball ball = new Ball(39, App.TOPBAR + App.CELLSIZE, 2, 0, "B0", images.get("B0"));

        ball.update(board, images, App.BOARD_WIDTH, App.BOARD_HEIGHT, new ArrayList<List<PVector>>());

        assertEquals(-2, ball.getVx());
        assertEquals("B1", ball.getType());
        assertSame(images.get("B1"), ball.getImage());
    }

    @Test
    public void holeColourMarkerIsNotAColouredWall() {
        // The number in H2 identifies a blue hole and must not reflect or recolour a ball.
        char[][] board = emptyBoard();
        board[1][1] = 'H';
        board[1][2] = '2';
        Map<String, PImage> images = ballImages();
        Ball ball = new Ball(39, App.TOPBAR + App.CELLSIZE, 2, 0, "B0", images.get("B0"));

        ball.update(board, images, App.BOARD_WIDTH, App.BOARD_HEIGHT, new ArrayList<List<PVector>>());

        assertEquals(2, ball.getVx());
        assertEquals("B0", ball.getType());
        assertSame(images.get("B0"), ball.getImage());
    }

    @Test
    public void wallAndScreenEdgesReflectWithoutRemovingBall() {
        // Both a map wall and an otherwise open board edge are solid reflectors.
        char[][] board = emptyBoard();
        board[2][1] = 'X';
        Ball wallBall = new Ball(App.CELLSIZE, App.TOPBAR + 39, 0, 2, "B0", new PImage());
        wallBall.update(board, ballImages(), App.BOARD_WIDTH, App.BOARD_HEIGHT, new ArrayList<List<PVector>>());
        assertEquals(-2, wallBall.getVy());

        Ball edgeBall = new Ball(0, App.TOPBAR + App.CELLSIZE, -2, 0, "B0", new PImage());
        edgeBall.update(emptyBoard(), ballImages(), App.BOARD_WIDTH, App.BOARD_HEIGHT, new ArrayList<List<PVector>>());
        assertEquals(2, edgeBall.getVx());
        assertFalse(edgeBall.isRemoved());
    }

    @Test
    public void inkLineReflectsTheBallAndIsConsumed() {
        // A reflected ball removes only the line that it hit.
        Ball ball = new Ball(100, 100, 2, 0, "B0", new PImage());
        List<PVector> line = new ArrayList<PVector>();
        line.add(new PVector(100, 80));
        line.add(new PVector(100, 140));
        List<List<PVector>> lines = new ArrayList<List<PVector>>();
        lines.add(line);

        ball.update(emptyBoard(), ballImages(), App.BOARD_WIDTH, App.BOARD_HEIGHT, lines);

        assertEquals(-2, ball.getVx(), 0.001);
        assertTrue(line.isEmpty());
    }

    @Test
    public void attractionChangesVelocityAndShrinksBall() {
        // Entering the 32-pixel pull range adds force toward the hole and scales the sprite down.
        Ball ball = new Ball(80, App.TOPBAR + 80, 0, 0, "B0", new PImage());
        ball.attractTowards(100, App.TOPBAR + 100);

        assertTrue(ball.getVx() > 0.05f);
        assertTrue(ball.getVy() > 0.05f);
        assertTrue(ball.getScale() > 0 && ball.getScale() < 1);
        ball.clearAttraction();
        assertEquals(1, ball.getScale());
    }

    @Test
    public void inspectionGuideMovesBallTowardTarget() {
        // Auto-solve movement must bring a ball closer while preserving gradual motion.
        Ball ball = new Ball(20, App.TOPBAR + 20, 2, 2, "B0", new PImage());
        float originalDistance = PVector.dist(
                new PVector(ball.getCentreX(), ball.getCentreY()), new PVector(300, 300));

        ball.guideTowards(300, 300);

        float guidedDistance = PVector.dist(
                new PVector(ball.getCentreX(), ball.getCentreY()), new PVector(300, 300));
        assertTrue(guidedDistance < originalDistance);
        assertTrue(Math.abs(ball.getVx()) <= 4);
        assertTrue(Math.abs(ball.getVy()) <= 4);
    }

    @Test
    public void appResolvesHoleColoursAndSpawnPoints() {
        // Hole colour parsing is safe for invalid input and a spawner maps to the correct pixel position.
        App app = new App();
        assertEquals("grey", app.getHoleColor("H0"));
        assertEquals("yellow", app.getHoleColor("H4"));
        assertEquals("", app.getHoleColor("H"));
        assertEquals("", app.getHoleColor("X1"));

        app.tiles[5][5] = 'S';
        PVector spawn = app.findSpawnPoint('S');
        assertEquals(5 * App.CELLSIZE, spawn.x);
        assertEquals(5 * App.CELLSIZE + App.TOPBAR, spawn.y);
    }

    @Test
    public void configuredBallSpawnsWithAnAllowedVelocity() {
        // Spawned balls use a valid sprite and one of the four required ±2 trajectories.
        App app = new App();
        app.tiles[2][2] = 'S';
        app.images.put("B0", new PImage());
        app.spawnNextBallAtS("B0");

        assertEquals(1, app.balls.size());
        Ball spawned = app.balls.get(0);
        assertEquals("B0", spawned.getType());
        assertTrue(Math.abs(spawned.getVx()) == 2);
        assertTrue(Math.abs(spawned.getVy()) == 2);
    }

    @Test
    public void captureUsesGlobalScoreTablesWhenLevelHasOnlyModifiers() throws Exception {
        // The supplied config keeps score tables globally; a successful capture must still add its value.
        App app = new App();
        try (FileReader reader = new FileReader("config.json")) {
            Field configField = App.class.getDeclaredField("config");
            configField.setAccessible(true);
            configField.set(app, new JSONObject(reader));
        }
        app.loadLevelData(0);
        app.tiles[1][1] = 'H';
        app.tiles[1][2] = '2';
        Method collectHoles = App.class.getDeclaredMethod("collectHoles");
        collectHoles.setAccessible(true);
        collectHoles.invoke(app);

        Ball blueBall = new Ball(52, 116, 0, 0, "blue", new PImage());
        app.checkBallHoleCollision(blueBall);

        assertTrue(blueBall.isRemoved());
        assertEquals(50, app.getScore());
    }

    @Test
    public void lineSegmentStoresAllFourCoordinates() {
        // The small geometry value object preserves its end point's y-coordinate.
        LineSegment segment = new LineSegment(1, 2, 3, 4);
        assertEquals(1, segment.x1);
        assertEquals(2, segment.y1);
        assertEquals(3, segment.x2);
        assertEquals(4, segment.y2);
    }
}
