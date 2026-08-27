package inkball;

import java.util.List;
import java.util.Map;

import processing.core.PApplet;
import processing.core.PImage;
import processing.core.PVector;

/**
 * A ball that moves through the board and reflects from walls and ink lines.
 * Its position represents the top-left corner of its unscaled sprite.
 */
public class Ball {
    private static final float FALLBACK_SIZE = 24.0f;
    // The brief suggests approximately 0.5%; 0.8% gives a slightly stronger,
    // more reliable pull without making balls snap unnaturally to the centre.
    private static final float HOLE_ATTRACTION = 0.008f;
    private static final float HOLE_DRAG = 0.99f;

    private float x;
    private float y;
    private float vx;
    private float vy;
    private String type;
    private PImage image;
    private boolean removed;
    private float scale = 1.0f;

    public Ball(float x, float y, float vx, float vy, String type, PImage image) {
        this.x = x;
        this.y = y;
        this.vx = vx;
        this.vy = vy;
        this.type = type;
        this.image = image;
    }

    public String getType() { return type; }
    public float getVx() { return vx; }
    public float getVy() { return vy; }
    public float getX() { return x; }
    public float getY() { return y; }
    public float getScale() { return scale; }
    public PImage getImage() { return image; }
    public boolean isRemoved() { return removed; }
    public void setVx(float vx) { this.vx = vx; }
    public void setVy(float vy) { this.vy = vy; }
    public void setRemoved(boolean removed) { this.removed = removed; }

    public float getCentreX() { return x + getBaseWidth() / 2.0f; }
    public float getCentreY() { return y + getBaseHeight() / 2.0f; }
    public float getRadius() { return getBaseWidth() * scale / 2.0f; }

    /**
     * Applies the specified hole's attraction force and reduces the sprite in
     * proportion to the remaining distance to its centre.
     */
    public void attractTowards(float holeCentreX, float holeCentreY) {
        float dx = holeCentreX - getCentreX();
        float dy = holeCentreY - getCentreY();
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        vx = (vx + dx * HOLE_ATTRACTION) * HOLE_DRAG;
        vy = (vy + dy * HOLE_ATTRACTION) * HOLE_DRAG;
        scale = Math.max(0.05f, Math.min(1.0f, distance / App.CELLSIZE));
    }

    /** Restores normal drawing size after the ball leaves a hole's pull range. */
    public void clearAttraction() {
        scale = 1.0f;
    }

    /**
     * Moves directly toward a target for the inspection cheat. This deliberately
     * bypasses walls and ink, but leaves capture, scoring, and level completion
     * to the normal App logic.
     */
    public void guideTowards(float targetX, float targetY) {
        float dx = targetX - getCentreX();
        float dy = targetY - getCentreY();
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        if (distance == 0) return;

        float speed = distance > App.CELLSIZE ? 4.0f : Math.max(0.75f, Math.min(3.0f, distance * 0.18f));
        vx = dx / distance * speed;
        vy = dy / distance * speed;
        x += vx;
        y += vy;
        scale = distance <= App.CELLSIZE
                ? Math.max(0.05f, distance / App.CELLSIZE)
                : 1.0f;
    }

    /** Moves one frame and resolves collisions with board edges, walls, and ink. */
    public void update(char[][] tiles, Map<String, PImage> images, int boardWidth,
            int boardHeight, List<List<PVector>> lines) {
        if (removed) return;

        float nextX = x + vx;
        float nextY = y + vy;
        float visualWidth = getBaseWidth() * scale;
        float visualHeight = getBaseHeight() * scale;

        float attemptedLeft = leftAt(nextX, visualWidth);
        float currentTop = topAt(y, visualHeight);
        boolean horizontalCollision = attemptedLeft < 0 || attemptedLeft + visualWidth > boardWidth * App.CELLSIZE
                || collidesWithWall(tiles, attemptedLeft, currentTop, visualWidth, visualHeight,
                        boardWidth, boardHeight);
        if (horizontalCollision) {
            char wallColour = colouredWallIn(tiles, attemptedLeft, currentTop, visualWidth, visualHeight,
                    boardWidth, boardHeight);
            vx = -vx;
            nextX = x;
            changeColor(wallColour, ' ', images);
        }

        float currentLeft = leftAt(nextX, visualWidth);
        float attemptedTop = topAt(nextY, visualHeight);
        boolean verticalCollision = attemptedTop < App.TOPBAR
                || attemptedTop + visualHeight > App.TOPBAR + boardHeight * App.CELLSIZE
                || collidesWithWall(tiles, currentLeft, attemptedTop, visualWidth, visualHeight,
                        boardWidth, boardHeight);
        if (verticalCollision) {
            char wallColour = colouredWallIn(tiles, currentLeft, attemptedTop, visualWidth, visualHeight,
                    boardWidth, boardHeight);
            vy = -vy;
            nextY = y;
            changeColor(wallColour, ' ', images);
        }

        if (collidesWithInk(nextX, nextY, lines)) {
            nextX = x;
            nextY = y;
        }

        x = nextX;
        y = nextY;
    }

    private float getBaseWidth() {
        return image == null || image.width <= 0 ? FALLBACK_SIZE : image.width;
    }

    private float getBaseHeight() {
        return image == null || image.height <= 0 ? FALLBACK_SIZE : image.height;
    }

    private float leftAt(float unscaledX, float visualWidth) {
        return unscaledX + (getBaseWidth() - visualWidth) / 2.0f;
    }

    private float topAt(float unscaledY, float visualHeight) {
        return unscaledY + (getBaseHeight() - visualHeight) / 2.0f;
    }

    private boolean collidesWithWall(char[][] tiles, float left, float top, float width, float height,
            int boardWidth, int boardHeight) {
        int firstCol = (int) Math.floor(left / App.CELLSIZE);
        int lastCol = (int) Math.floor((left + width - 0.001f) / App.CELLSIZE);
        int firstRow = (int) Math.floor((top - App.TOPBAR) / App.CELLSIZE);
        int lastRow = (int) Math.floor((top + height - App.TOPBAR - 0.001f) / App.CELLSIZE);
        for (int row = firstRow; row <= lastRow; row++) {
            for (int col = firstCol; col <= lastCol; col++) {
                if (row < 0 || row >= boardHeight || col < 0 || col >= boardWidth
                        || isWall(tiles, row, col)) {
                    return true;
                }
            }
        }
        return false;
    }

    private char colouredWallIn(char[][] tiles, float left, float top, float width, float height,
            int boardWidth, int boardHeight) {
        int firstCol = Math.max(0, (int) Math.floor(left / App.CELLSIZE));
        int lastCol = Math.min(boardWidth - 1, (int) Math.floor((left + width - 0.001f) / App.CELLSIZE));
        int firstRow = Math.max(0, (int) Math.floor((top - App.TOPBAR) / App.CELLSIZE));
        int lastRow = Math.min(boardHeight - 1,
                (int) Math.floor((top + height - App.TOPBAR - 0.001f) / App.CELLSIZE));
        for (int row = firstRow; row <= lastRow; row++) {
            for (int col = firstCol; col <= lastCol; col++) {
                char tile = tiles[row][col];
                if (tile >= '1' && tile <= '4' && !isHoleColourMarker(tiles, row, col)) return tile;
            }
        }
        return ' ';
    }

    private boolean isWall(char[][] tiles, int row, int col) {
        char tile = tiles[row][col];
        return tile == 'X' || (tile >= '1' && tile <= '4' && !isHoleColourMarker(tiles, row, col));
    }

    /** The digit immediately following H describes a hole; it is not a wall. */
    private boolean isHoleColourMarker(char[][] tiles, int row, int col) {
        return col > 0 && tiles[row][col - 1] == 'H';
    }

    private boolean collidesWithInk(float nextX, float nextY, List<List<PVector>> lines) {
        PVector nextCentre = new PVector(nextX + getBaseWidth() / 2.0f, nextY + getBaseHeight() / 2.0f);
        for (List<PVector> line : lines) {
            for (int i = 0; i + 1 < line.size(); i++) {
                PVector first = line.get(i);
                PVector second = line.get(i + 1);
                float segmentLength = first.dist(second);
                if (segmentLength == 0) continue;
                if (first.dist(nextCentre) + second.dist(nextCentre) < segmentLength + getRadius()) {
                    reflectVelocity(first, second);
                    line.clear();
                    return true;
                }
            }
        }
        return false;
    }

    protected void changeColor(char tile1, char tile2, Map<String, PImage> images) {
        char colour = tile1 >= '1' && tile1 <= '4' ? tile1 : tile2;
        if (colour < '1' || colour > '4') return;
        PImage replacement = images.get("B" + colour);
        if (replacement != null) {
            image = replacement;
            type = "B" + colour;
        }
    }

    /** Reflects the velocity vector around the normal of an ink-line segment. */
    protected void reflectVelocity(PVector first, PVector second) {
        PVector direction = PVector.sub(second, first);
        if (direction.magSq() == 0) return;
        direction.normalize();
        PVector normal = new PVector(-direction.y, direction.x);
        float dotProduct = vx * normal.x + vy * normal.y;
        vx -= 2.0f * dotProduct * normal.x;
        vy -= 2.0f * dotProduct * normal.y;
    }

    protected float distToSegment(PVector point, PVector first, PVector second) {
        PVector segment = PVector.sub(second, first);
        float lengthSquared = segment.magSq();
        if (lengthSquared == 0) return point.dist(first);
        float t = Math.max(0, Math.min(1, PVector.sub(point, first).dot(segment) / lengthSquared));
        return point.dist(PVector.add(first, PVector.mult(segment, t)));
    }

    public void draw(PApplet app) {
        if (removed || image == null) return;
        float width = getBaseWidth() * scale;
        float height = getBaseHeight() * scale;
        app.image(image, leftAt(x, width), topAt(y, height), width, height);
    }
}
