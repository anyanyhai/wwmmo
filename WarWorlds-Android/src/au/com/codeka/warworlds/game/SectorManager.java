package au.com.codeka.warworlds.game;

import java.util.Map;
import java.util.TreeMap;

import au.com.codeka.warworlds.common.util.Pair;

/**
 * This class "manages" the list of \c StarfieldSector's that we have loaded
 * and is responsible for loading new sectors and freeing old ones as we
 * scroll around.
 * 
 * The way we manage the scrolling position is we have a "sectorX, sectorY"
 * which determines which sector is in the centre of the view, then we have
 * an "offsetX, offsetY" which is a pixel offset to apply when drawing the
 * sectors (so you can smoothly scroll, of course).
 */
public class SectorManager {
    private static SectorManager sInstance = new SectorManager();

    public static SectorManager getInstance() {
        return sInstance;
    }

    private int mRadius = 1;

    private long mSectorX;
    private long mSectorY;
    private int mOffsetX;
    private int mOffsetY;
    private Map<Pair<Long, Long>, StarfieldSector> mSectors;

    private SectorManager() {
        mSectorX = mSectorY = 0;
        mOffsetX = mOffsetY = 0;
        mSectors = new TreeMap<Pair<Long, Long>, StarfieldSector>();
        this.scrollTo(0, 0, 0, 0);
    }

    public StarfieldSector getSector(long sectorX, long sectorY) {
        Pair<Long, Long> key = new Pair<Long, Long>(sectorX, sectorY);
        if (mSectors.containsKey(key)) {
            return mSectors.get(key);
        } else {
            return null;
        }
    }

    public long getSectorCentreX() {
        return mSectorX;
    }

    public long getSectorCentreY() {
        return mSectorY;
    }

    public int getRadius() {
        return mRadius;
    }

    public void scrollTo(long sectorX, long sectorY, int offsetX, int offsetY) {
        mSectorX = sectorX;
        mSectorY = sectorY;
        mOffsetX = offsetX;
        mOffsetY = offsetY;

        Map<Pair<Long, Long>, StarfieldSector> newSectors = 
                new TreeMap<Pair<Long, Long>, StarfieldSector>();
        for(sectorY = mSectorY - mRadius; sectorY <= mSectorY + mRadius; sectorY++) {
            for(sectorX = mSectorX - mRadius; sectorX <= mSectorX + mRadius; sectorX++) {
                Pair<Long, Long> key = new Pair<Long, Long>(sectorX, sectorY);
                if (mSectors.containsKey(key)) {
                    newSectors.put(key, mSectors.get(key));
                } else {
                    newSectors.put(key, new StarfieldSector(sectorX, sectorY));
                }
            }
        }

        mSectors = newSectors;
    }
}
