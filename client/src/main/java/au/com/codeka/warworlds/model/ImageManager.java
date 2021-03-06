package au.com.codeka.warworlds.model;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;

import android.graphics.Bitmap;
import au.com.codeka.common.Colour;
import au.com.codeka.common.Image;
import au.com.codeka.common.Log;
import au.com.codeka.common.Vector3;
import au.com.codeka.planetrender.PlanetRenderer;
import au.com.codeka.planetrender.Template;
import au.com.codeka.planetrender.TemplateException;
import au.com.codeka.warworlds.App;
import au.com.codeka.warworlds.GlobalOptions;
import au.com.codeka.warworlds.eventbus.EventBus;

/**
 * This is the base class for the \c StarImageManagaer and \c PlanetImageManager.
 */
public abstract class ImageManager {
    private static final Log log = new Log("ImageManager");

    private static final int MAX_GENERATE_QUEUE_SIZE = 50;

    public static EventBus eventBus = new EventBus();

    private Queue<QueuedGenerate> mGenerateQueue =
            new ArrayBlockingQueue<QueuedGenerate>(MAX_GENERATE_QUEUE_SIZE);
    private Thread mGenerateThread;
    private Map<String, Template> mTemplates = new HashMap<String, Template>();
    private Map<String, String[]> mFileLists = new HashMap<String, String[]>();
    private double mPixelScale;

    /**
     * Gets the \c Sprite for the given object at the given size.. If no image has been generated
     * yet, \c null is returned and you should wait for the ImageGenerated event.
     * 
     * To facilitate adding new images more easily, we check the asset directory each
     * time for the template. If the template would cause a different image to be
     * generated, then we don't return the cached image.
     * 
     * @param context The \c Context that you're running in.
     * @param key The key of the object (planet or star) that you want to generate an image for.
     * @param size The size, in device pixels, of the image you're after.
     * @param extra An object that the subclass passes in, which we'll pass back when getting
     *         things like the sun direction and planet size, etc.
     * @return A \c Bitmap with an image of the planet or star, or \c null if the image has
     *          not been generated yet.
     */
    protected Sprite getSprite(final String key, int size, Object extra) {
        GlobalOptions opts = new GlobalOptions();
        if (!opts.uniqueStarsAndPlanets()) {
            return null;
        }

        final String cacheKey = String.format(Locale.ENGLISH, "%s_%d", key, size);
        if (isInGenerateQueue(cacheKey)) {
            // if we've already queued up this planet/star, just give up now
            return null;
        }

        if (mPixelScale == 0) {
            mPixelScale = App.i.getResources().getDisplayMetrics().density;
        }

        long startTime = System.nanoTime();
        Template tmpl = getTemplate(extra);
        if (tmpl == null) {
            return null;
        }

        final File cacheFile = new File(getCachePath(tmpl, cacheKey));
        if (cacheFile.exists()) {
            String fullPath = cacheFile.getAbsolutePath();
            log.debug("Loading cached image: %s", fullPath);
            return SpriteManager.i.getSimpleSprite(fullPath, false);
        } else {
            long endTime = System.nanoTime();
            log.debug("No cached image (after %.4fms), generating: %s",
                    (endTime - startTime) / 1000000.0,
                    cacheFile.getAbsolutePath());

            addToGenerateQueue(new QueuedGenerate(tmpl, key, size, cacheKey,
                                                  cacheFile.getAbsolutePath(), extra));
            ensureGenerateThread();

            return null;
        }
    }

    public void clearCaches() {
        mTemplates.clear();
    }

    /**
     * Loads the \c Template for the given \c Planet.
     */
    protected Template loadTemplate(String basePath, String key) {
        String[] fileNames = mFileLists.get(basePath);
        if (fileNames == null) {
            try {
                // for some reason, this can be incredibly slow on some devices (notably, my Galaxy
                // Note 8), that's why we cache it.
                fileNames = App.i.getAssets().list(basePath);
                mFileLists.put(basePath, fileNames);
            } catch(IOException e) {
                return null; // should never happen!
            }
        }

        long seed = key.hashCode();
        Random rand = new Random(seed);

        String fullPath = basePath + "/";
        if (fileNames.length == 0) {
            return null;
        } else if (fileNames.length == 1) {
            fullPath += fileNames[0];
        } else {
            fullPath += fileNames[rand.nextInt(fileNames.length)];
        }

        Template tmpl = mTemplates.get(fullPath);
        if (tmpl == null) {
            InputStream ins = null;
            try {
                ins = App.i.getAssets().open(fullPath);
                tmpl = Template.parse(ins);
            } catch (IOException e) {
                log.error("Error loading object definition: %s", fullPath, e);
            } catch (TemplateException e) {
                log.error("Error parsing object definition: %s", fullPath, e);
            } finally {
                if (ins != null) {
                    try {
                        ins.close();
                    } catch (IOException e) {
                    }
                }
            }

            if (tmpl != null) {
                tmpl.setName(fullPath.replace(File.separatorChar, '-').replace(".xml", ""));
                mTemplates.put(fullPath, tmpl);
            }
        }

        return tmpl;
    }

    /**
     * Gets the path to the cached version of the image generated by the given
     * \c Template.
     */
    private static String getCachePath(Template tmpl, String cacheKey) {
        File cacheDir = App.i.getCacheDir();

        String fullPath = cacheDir.getAbsolutePath() + File.separator + "planets" + File.separator;
        fullPath += String.format("%s-%d-%s.png", tmpl.getName(),
                tmpl.getTemplateVersion(), cacheKey);

        return fullPath;
    }

    protected abstract Template getTemplate(Object extra);

    protected abstract Vector3 getSunDirection(Object extra);

    protected abstract double getPlanetSize(Object extra);

    /**
     * This is called in a background to actually generate the bitmap.
     * @param tmpl
     * @param outputPath
     */
    private boolean generateBitmap(QueuedGenerate item) {
        Vector3 sunDirection = getSunDirection(item.extra);

        // planet size ranges from 10 to 50, we convert that to 5..10 which is what we apply to
        // the planet renderer itself
        double size = getPlanetSize(item.extra);

        PlanetRenderer renderer;
        if (item.tmpl.getTemplate() instanceof Template.PlanetTemplate) {
            Template.PlanetTemplate planetTemplate = (Template.PlanetTemplate) item.tmpl.getTemplate();
            planetTemplate.setSunLocation(sunDirection);
            planetTemplate.setPlanetSize(size);

            long seed = item.key.hashCode();
            Random rand = new Random(seed);

            renderer = new PlanetRenderer(planetTemplate, rand);
        } else {
            Template.PlanetsTemplate planetsTemplate = (Template.PlanetsTemplate) item.tmpl.getTemplate();
            for (Template.PlanetTemplate planetTemplate : planetsTemplate.getParameters(Template.PlanetTemplate.class)) {
                planetTemplate.setSunLocation(sunDirection);
                planetTemplate.setPlanetSize(size);
            }

            long seed = item.key.hashCode();
            Random rand = new Random(seed);

            renderer = new PlanetRenderer(planetsTemplate, rand);
        }

        int imgSize = (int)(item.size * mPixelScale);

        long startTime = System.nanoTime();
        Image img = new Image(imgSize, imgSize, Colour.TRANSPARENT);
        renderer.render(img);
        Bitmap bmp;
        try {
            bmp = Bitmap.createBitmap(img.getArgb(), imgSize, imgSize, Bitmap.Config.ARGB_8888);
        } catch(OutOfMemoryError e) {
            return false;
        }
        long endTime = System.nanoTime();

        Vector3.pool.release(sunDirection);
        log.debug("Rendered %dx%d image in %.4fms.",
                imgSize, imgSize, (endTime - startTime) / 1000000.0);

        File outputFile = new File(item.outputPath);
        File outputDirectory = new File(outputFile.getParent());
        if (!outputDirectory.exists()) {
            outputDirectory.mkdirs();
        }

        FileOutputStream fos;
        try {
            fos = new FileOutputStream(item.outputPath);
            bmp.compress(Bitmap.CompressFormat.PNG, 90, fos);
        } catch (FileNotFoundException e) {
            log.error("Error writing to cache file.", e);
        }
        bmp.recycle();

        Sprite sprite = SpriteManager.i.getSimpleSprite(outputFile.getAbsolutePath(), false);
        eventBus.publish(new SpriteGeneratedEvent(item.key, sprite));
        return true;
    }

    /**
     * Makes sure the generate thread is running, and spins one up if it's not.
     */
    private void ensureGenerateThread() {
        synchronized(mGenerateQueue) {
            if (mGenerateThread == null) {
                mGenerateThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        generateThreadProc();

                        // when it finishes, make sure we set the thread to null so
                        // we know to start it up again.
                        synchronized(mGenerateQueue) {
                            mGenerateThread = null;
                        }
                    }
                });

                // make it low priority -- UI must stay responsive!
                mGenerateThread.setPriority(Thread.MIN_PRIORITY);
                mGenerateThread.start();
            }
        }
    }

    private void generateThreadProc() {
        QueuedGenerate item;
        synchronized(mGenerateQueue) {
            item = mGenerateQueue.poll();
        }

        while (item != null) {
            if (!generateBitmap(item)) {
                System.gc();
                continue;
            }

            synchronized(mGenerateQueue) {
                item = mGenerateQueue.poll();
            }
        }
    }

    private void addToGenerateQueue(QueuedGenerate item) {
        synchronized(mGenerateQueue) {
            // only add if we're not already generating this item
            if (!isInGenerateQueue(item.cacheKey)) {
                if (mGenerateQueue.size() < MAX_GENERATE_QUEUE_SIZE) {
                    mGenerateQueue.add(item);
                }
            }
        }
    }

    /**
     * Checks whether we've already requests the given planet be generated (no need to
     * go through all the bother a second time).
     */
    private boolean isInGenerateQueue(String cacheKey) {
        synchronized(mGenerateQueue) {
            boolean found = false;
            for (QueuedGenerate qg : mGenerateQueue) {
                if (qg.cacheKey.equals(cacheKey)) {
                    found = true;
                    break;
                }
            }

            return found;
        }
    }

    class QueuedGenerate {
        public Template tmpl;
        public String outputPath;
        public String key;
        public int size;
        public String cacheKey;
        public Object extra;

        public QueuedGenerate(Template tmpl, String key, int size, String cacheKey,
                               String outputPath, Object extra) {
            this.tmpl = tmpl;
            this.outputPath = outputPath;
            this.key = key;
            this.size = size;
            this.cacheKey = cacheKey;
            this.extra = extra;
        }
    }

    public static class SpriteGeneratedEvent {
        public String key;
        public Sprite sprite;

        public SpriteGeneratedEvent(String key, Sprite sprite) {
            this.key = key;
            this.sprite = sprite;
        }
    }
}
