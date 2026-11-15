package br.tradutorimagens.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.Base64;
import android.util.LruCache;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/**
 * Mesmo fluxo da extensão: baixa a imagem (só em memória) -> fatia -> OCR -> agrupa linhas em
 * balões -> amostra a cor de fundo -> traduz -> devolve blocos em frações (0..1) da imagem.
 */
final class TranslatorEngine {

    private static final int MAX_W = 1400;
    private static final int SLICE_H = 2000;
    private static final Pattern CJK = Pattern.compile("[\\u3040-\\u30ff\\u3400-\\u9fff\\uac00-\\ud7af]");
    private static final Pattern KANA = Pattern.compile("[\\u3040-\\u30ff]");
    private static final Pattern HANGUL = Pattern.compile("[\\uac00-\\ud7af]");
    private static final Pattern HAN = Pattern.compile("[\\u3400-\\u9fff]");
    private static final Pattern LATIN = Pattern.compile("[A-Za-z]{2}");

    private final ExecutorService jobs = Executors.newFixedThreadPool(2);
    private final ExecutorService translators = Executors.newFixedThreadPool(4);
    private final Map<String, TextRecognizer> recognizers = new HashMap<>();
    private final LruCache<String, String> cache = new LruCache<>(60);
    private volatile String lastScript = "ja";

    void submit(Runnable r) {
        jobs.execute(r);
    }

    // ------------------------------------------------------------------ API

    String translateUrl(String url, String pageUrl, String cookie, String ua,
                        String target, String source) throws Exception {
        String key = target + "|" + source + "|u" + url;
        String hit = cache.get(key);
        if (hit != null) return hit;
        byte[] bytes = download(url, pageUrl, cookie, ua);
        String json = process(bytes, target, source);
        cache.put(key, json);
        return json;
    }

    String translateData(String dataUrl, String target, String source) throws Exception {
        String key = target + "|" + source + "|d" + dataUrl.length() + ":" + dataUrl.hashCode();
        String hit = cache.get(key);
        if (hit != null) return hit;
        int comma = dataUrl.indexOf(',');
        if (comma < 0) throw new IOException("Imagem inválida");
        byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
        String json = process(bytes, target, source);
        cache.put(key, json);
        return json;
    }

    // ------------------------------------------------------------ download

    private byte[] download(String url, String pageUrl, String cookie, String ua) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            if (ua != null) c.setRequestProperty("User-Agent", ua);
            if (pageUrl != null && !pageUrl.isEmpty()) c.setRequestProperty("Referer", pageUrl);
            if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
            c.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
            int code = c.getResponseCode();
            if (code >= 400) throw new IOException("Não consegui baixar a imagem (HTTP " + code + ")");
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16384];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 40L * 1024 * 1024) throw new IOException("Imagem grande demais");
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    // -------------------------------------------------------------- pipeline

    private static final class Block {
        int l, t, r, b;
        String text;
        boolean vertical;
        int[] bg;
        // resultado final, em frações da imagem inteira
        double x, y, w, h;
    }

    private static final class Ln {
        int l, t, r, b;
        String text;
    }

    private String process(byte[] bytes, String target, String source) throws Exception {
        BitmapFactory.Options bo = new BitmapFactory.Options();
        bo.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bo);
        int srcW = bo.outWidth, srcH = bo.outHeight;
        if (srcW <= 0 || srcH <= 0) throw new IOException("Formato de imagem não suportado");

        BitmapRegionDecoder dec = null;
        try {
            dec = BitmapRegionDecoder.newInstance(bytes, 0, bytes.length, false);
        } catch (Exception ignored) {
            dec = null;
        }
        Bitmap whole = null;
        if (dec == null) {
            whole = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (whole == null) throw new IOException("Não consegui abrir a imagem");
        }

        double scale = Math.min(1.0, (double) MAX_W / srcW);
        List<Block> all = new ArrayList<>();

        int srcTop = 0;
        while (srcTop < srcH) {
            int srcSlice = Math.min(srcH - srcTop, (int) Math.round(SLICE_H / scale));
            Bitmap region = flatten(decodeRegion(dec, whole, srcW, srcTop, srcSlice));
            srcSlice = region.getHeight();

            Bitmap bmp = region;
            if (scale < 1.0) {
                int nw = Math.max(1, (int) Math.round(region.getWidth() * scale));
                int nh = Math.max(1, (int) Math.round(region.getHeight() * scale));
                bmp = Bitmap.createScaledBitmap(region, nw, nh, true);
                if (bmp != region) region.recycle();
            }

            // procura uma linha "vazia" perto do fim da fatia para não partir um balão ao meio
            if (srcTop + srcSlice < srcH) {
                int cut = findCut(bmp);
                if (cut > 0 && cut < bmp.getHeight()) {
                    Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), cut);
                    srcSlice = Math.max(1, (int) Math.round(srcSlice * ((double) cut / bmp.getHeight())));
                    bmp.recycle();
                    bmp = cropped;
                }
            }

            int bw = bmp.getWidth(), bh = bmp.getHeight();
            List<Block> blocks = cluster(recognize(bmp, source));
            for (Block b : blocks) {
                int pad = 3;
                b.l = Math.max(0, b.l - pad);
                b.t = Math.max(0, b.t - pad);
                b.r = Math.min(bw, b.r + pad);
                b.b = Math.min(bh, b.b + pad);
                b.bg = sampleBg(bmp, b.l, b.t, b.r, b.b);
                b.x = (double) b.l / bw;
                b.w = (double) (b.r - b.l) / bw;
                b.y = (srcTop + (double) b.t / bh * srcSlice) / srcH;
                b.h = ((double) (b.b - b.t) / bh * srcSlice) / srcH;
                all.add(b);
            }
            bmp.recycle();
            srcTop += srcSlice;
        }
        if (dec != null) dec.recycle();
        if (whole != null) whole.recycle();

        // tradução em paralelo
        List<Callable<String>> tasks = new ArrayList<>();
        for (final Block b : all) {
            tasks.add(new Callable<String>() {
                @Override
                public String call() {
                    try {
                        return translate(b.text, target);
                    } catch (Exception e) {
                        return null;
                    }
                }
            });
        }
        List<Future<String>> futures = translators.invokeAll(tasks);

        JSONArray out = new JSONArray();
        for (int i = 0; i < all.size(); i++) {
            String tr = futures.get(i).get();
            Block b = all.get(i);
            if (tr == null || tr.trim().isEmpty()) continue;
            if (tr.trim().equalsIgnoreCase(b.text.trim())) continue;
            int[] bg = b.bg;
            double lum = 0.299 * bg[0] + 0.587 * bg[1] + 0.114 * bg[2];
            JSONObject o = new JSONObject();
            o.put("x", b.x);
            o.put("y", b.y);
            o.put("w", b.w);
            o.put("h", b.h);
            o.put("text", tr);
            o.put("vertical", b.vertical);
            o.put("bg", "rgb(" + bg[0] + "," + bg[1] + "," + bg[2] + ")");
            o.put("fg", lum > 140 ? "#111" : "#fff");
            out.put(o);
        }
        return out.toString();
    }

    private Bitmap decodeRegion(BitmapRegionDecoder dec, Bitmap whole, int srcW, int top, int h) throws IOException {
        if (dec != null) {
            Bitmap b = dec.decodeRegion(new Rect(0, top, srcW, top + h), new BitmapFactory.Options());
            if (b == null) throw new IOException("Falha ao decodificar a imagem");
            return b;
        }
        int hh = Math.min(h, whole.getHeight() - top);
        return Bitmap.createBitmap(whole, 0, top, Math.min(srcW, whole.getWidth()), hh);
    }

    /** PNG/WebP com transparência vira fundo branco (o OCR não lida bem com alfa). */
    private Bitmap flatten(Bitmap src) {
        if (!src.hasAlpha()) return src;
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.WHITE);
        c.drawBitmap(src, 0, 0, null);
        src.recycle();
        return out;
    }

    private int findCut(Bitmap bmp) {
        int bh = bmp.getHeight(), bw = bmp.getWidth();
        int bandTop = Math.max(600, bh - 350);
        if (bh - bandTop < 20) return bh;
        int cols = Math.min(bw, 200);
        int step = Math.max(1, bw / cols);
        int[] row = new int[bw];
        double bestVar = Double.MAX_VALUE;
        int bestRow = bh - 1;
        for (int y = bandTop; y < bh; y++) {
            bmp.getPixels(row, 0, bw, 0, y, bw, 1);
            double sum = 0, sum2 = 0;
            int n = 0;
            for (int x = 0; x < bw; x += step) {
                int p = row[x];
                double l = 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p);
                sum += l;
                sum2 += l * l;
                n++;
            }
            double mean = sum / n;
            double v = sum2 / n - mean * mean;
            if (v <= bestVar) {
                bestVar = v;
                bestRow = y;
            }
        }
        return bestRow + 1;
    }

    // ------------------------------------------------------------------ OCR

    private synchronized TextRecognizer recognizer(String key) {
        TextRecognizer r = recognizers.get(key);
        if (r != null) return r;
        switch (key) {
            case "ja":
                r = TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build());
                break;
            case "ko":
                r = TextRecognition.getClient(new KoreanTextRecognizerOptions.Builder().build());
                break;
            case "zh":
                r = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
                break;
            default:
                r = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                key = "latin";
                break;
        }
        recognizers.put(key, r);
        return r;
    }

    private List<Ln> recognize(Bitmap bmp, String source) throws Exception {
        InputImage image = InputImage.fromBitmap(bmp, 0);
        List<String> order = new ArrayList<>();
        if ("auto".equals(source)) {
            order.add(lastScript);
            for (String k : Arrays.asList("ja", "ko", "zh", "latin")) {
                if (!order.contains(k)) order.add(k);
            }
        } else {
            order.add(source);
        }

        List<Ln> fallback = null;
        for (String key : order) {
            Text text = Tasks.await(recognizer(key).process(image));
            List<Ln> lines = collect(text);
            if (fallback == null) fallback = lines;
            if (!"auto".equals(source)) return lines;
            if (matchesScript(key, lines)) {
                lastScript = key;
                return lines;
            }
        }
        return fallback != null ? fallback : new ArrayList<Ln>();
    }

    private boolean matchesScript(String key, List<Ln> lines) {
        StringBuilder sb = new StringBuilder();
        for (Ln l : lines) sb.append(l.text).append(' ');
        String s = sb.toString();
        switch (key) {
            case "ja":
                return KANA.matcher(s).find();
            case "ko":
                return HANGUL.matcher(s).find();
            case "zh":
                return HAN.matcher(s).find() && !KANA.matcher(s).find();
            default:
                return LATIN.matcher(s).find();
        }
    }

    private List<Ln> collect(Text text) {
        List<Ln> out = new ArrayList<>();
        for (Text.TextBlock tb : text.getTextBlocks()) {
            for (Text.Line line : tb.getLines()) {
                Rect r = line.getBoundingBox();
                if (r == null) continue;
                String t = line.getText().trim();
                if (t.isEmpty() || !hasLetterOrDigit(t)) continue;
                if (r.width() < 6 || r.height() < 6) continue;
                Ln l = new Ln();
                l.l = r.left;
                l.t = r.top;
                l.r = r.right;
                l.b = r.bottom;
                l.text = t;
                out.add(l);
            }
        }
        return out;
    }

    private boolean hasLetterOrDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetterOrDigit(s.charAt(i))) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ balões

    private static int thick(Ln a) {
        return Math.min(a.r - a.l, a.b - a.t);
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private List<Block> cluster(List<Ln> lines) {
        int n = lines.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Ln a = lines.get(i), b = lines.get(j);
                double ta = thick(a), tb = thick(b);
                double ratio = ta / tb;
                if (ratio < 0.5 || ratio > 2) continue;
                double pad = 0.7 * Math.min(ta, tb);
                boolean overlap = a.l - pad < b.r && b.l - pad < a.r
                        && a.t - pad < b.b && b.t - pad < a.b;
                if (overlap) parent[find(parent, i)] = find(parent, j);
            }
        }

        Map<Integer, List<Ln>> groups = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int g = find(parent, i);
            List<Ln> list = groups.get(g);
            if (list == null) {
                list = new ArrayList<>();
                groups.put(g, list);
            }
            list.add(lines.get(i));
        }

        List<Block> blocks = new ArrayList<>();
        for (List<Ln> g : groups.values()) {
            int vertCount = 0;
            for (Ln l : g) if ((l.b - l.t) > 2 * (l.r - l.l)) vertCount++;
            final boolean vert = vertCount > g.size() / 2.0;

            Collections.sort(g, new java.util.Comparator<Ln>() {
                @Override
                public int compare(Ln a, Ln b) {
                    if (vert) {
                        int cx = Integer.compare((b.l + b.r), (a.l + a.r));
                        return cx != 0 ? cx : Integer.compare(a.t, b.t);
                    }
                    int cy = Integer.compare((a.t + a.b), (b.t + b.b));
                    return cy != 0 ? cy : Integer.compare(a.l, b.l);
                }
            });

            StringBuilder raw = new StringBuilder();
            for (Ln l : g) raw.append(l.text);
            boolean cjk = CJK.matcher(raw.toString()).find();

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < g.size(); i++) {
                if (i > 0 && !vert && !cjk) sb.append(' ');
                sb.append(g.get(i).text);
            }
            String text = sb.toString().replaceAll("\\s+", " ").trim();
            if (text.length() < 2) continue;

            Block b = new Block();
            b.l = Integer.MAX_VALUE;
            b.t = Integer.MAX_VALUE;
            b.r = Integer.MIN_VALUE;
            b.b = Integer.MIN_VALUE;
            for (Ln l : g) {
                b.l = Math.min(b.l, l.l);
                b.t = Math.min(b.t, l.t);
                b.r = Math.max(b.r, l.r);
                b.b = Math.max(b.b, l.b);
            }
            b.text = text;
            b.vertical = vert;
            blocks.add(b);
        }
        return blocks;
    }

    // ----------------------------------------------------- cor de fundo

    private int[] sampleBg(Bitmap bmp, int l, int t, int r, int b) {
        int x0 = Math.max(0, l), y0 = Math.max(0, t);
        int x1 = Math.min(bmp.getWidth(), r), y1 = Math.min(bmp.getHeight(), b);
        int w = x1 - x0, h = y1 - y0;
        if (w < 2 || h < 2) return new int[]{255, 255, 255};

        Map<Integer, long[]> buckets = new HashMap<>();
        int[] horiz = new int[w];
        int[] vert = new int[h];

        bmp.getPixels(horiz, 0, w, x0, y0, w, 1);
        addPixels(buckets, horiz, w);
        bmp.getPixels(horiz, 0, w, x0, y1 - 1, w, 1);
        addPixels(buckets, horiz, w);
        bmp.getPixels(vert, 0, 1, x0, y0, 1, h);
        addPixels(buckets, vert, h);
        bmp.getPixels(vert, 0, 1, x1 - 1, y0, 1, h);
        addPixels(buckets, vert, h);

        long[] best = null;
        for (long[] e : buckets.values()) {
            if (best == null || e[3] > best[3]) best = e;
        }
        if (best == null || best[3] == 0) return new int[]{255, 255, 255};
        return new int[]{(int) (best[0] / best[3]), (int) (best[1] / best[3]), (int) (best[2] / best[3])};
    }

    private void addPixels(Map<Integer, long[]> buckets, int[] px, int count) {
        for (int i = 0; i < count; i++) {
            int p = px[i];
            int r = Color.red(p), g = Color.green(p), bl = Color.blue(p);
            int k = ((r >> 5) << 6) | ((g >> 5) << 3) | (bl >> 5);
            long[] e = buckets.get(k);
            if (e == null) {
                e = new long[4];
                buckets.put(k, e);
            }
            e[0] += r;
            e[1] += g;
            e[2] += bl;
            e[3]++;
        }
    }

    // --------------------------------------------------------- tradução

    private String translate(String text, String tl) throws Exception {
        String u = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&dt=t&tl="
                + URLEncoder.encode(tl, "UTF-8") + "&q=" + URLEncoder.encode(text, "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (c.getResponseCode() >= 400) throw new IOException("Tradução indisponível");
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                JSONArray root = new JSONArray(out.toString("UTF-8"));
                JSONArray segs = root.getJSONArray(0);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < segs.length(); i++) {
                    JSONArray seg = segs.optJSONArray(i);
                    if (seg != null) sb.append(seg.optString(0, ""));
                }
                return sb.toString();
            }
        } finally {
            c.disconnect();
        }
    }
}
