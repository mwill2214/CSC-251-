import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.print.PrinterException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Concrete Pad & Chain-Link Fence Estimator (Swing GUI, CSV storage).
 *
 * Requires Java 17+.
 * Compile:  javac ConcreteFenceEstimator.java
 * Run:      java ConcreteFenceEstimator
 *
 * Data files are created automatically in ./estimator_data/:
 *   material_prices.csv  - unit prices and productivity factors (editable in the "Rates & Prices" tab)
 *   labor_rates.csv      - hourly labor rate by trade (editable in the "Rates & Prices" tab)
 *   work_hours_log.csv   - crew hours, appended on every saved estimate
 *   estimates.csv        - one record per saved estimate
 */
public class ConcreteFenceEstimator extends JFrame {
    // ==================== HOW THIS PROGRAM WORKS (big picture) ====================
    // 1. main() starts the Swing window on the Event Dispatch Thread.
    // 2. The constructor loads prices/rates from the CSV files (creating them with defaults the first time).
    // 3. The user fills in the tabs: Project Info -> Concrete Pad -> Fence (optional) -> Rates -> Discounts/Notes.
    // 4. Clicking Calculate calls buildEstimate(): it validates every field, then does ALL the math into one Est object.
    // 5. report(e) turns the Est object into the printable text report shown on the Output tab.
    // 6. Save / Export / Print reuse the same Est object: Save appends to estimates.csv and work_hours_log.csv.
    // Sample run (200 ft x 100 ft x 8 in, 8% waste, $165/CY): raw = 493.83 CY, with waste = 533.33 CY, concrete cost = $87,999.45.
    // =============================================================================

    private static final long serialVersionUID = 1L;

    // STEP A - File locations: every CSV the program reads or writes lives in the ./estimator_data/ folder.
    static final Path DATA_DIR = Paths.get("estimator_data");
    static final Path PRICES_CSV = DATA_DIR.resolve("material_prices.csv");
    static final Path LABOR_CSV = DATA_DIR.resolve("labor_rates.csv");
    static final Path HOURS_CSV = DATA_DIR.resolve("work_hours_log.csv");
    static final Path ESTIMATES_CSV = DATA_DIR.resolve("estimates.csv");

    // Trade names used as keys in labor_rates.csv and in the work-hours log.
    static final String CONCRETE_TRADE = "Concrete Crew";
    static final String FENCE_TRADE = "Fence Crew";

    // Regexes used for input validation: digits only, optional decimal part (no sign, no exponent, no letters).
    // STEP B - Input validation patterns: a field must match one of these (digits only) or it is rejected,
    // so negative numbers, letters, and symbols never reach the math. DEC = decimal number, INT = whole number.
    static final Pattern DEC = Pattern.compile("\\d+(\\.\\d+)?|\\.\\d+");
    static final Pattern INT = Pattern.compile("\\d{1,9}");

    static final double WASTE_MIN = 5, WASTE_MAX = 15;

    // Slab thickness choices (inches) and their menu labels. The last label is the "Custom" entry.
    static final double[] THICKNESS = {4, 5, 6, 8};
    static final String[] THICKNESS_LABELS = {
            "4 in - standard residential / light commercial",
            "5 in - standard commercial",
            "6 in - heavy-duty warehouse / forklift traffic",
            "8 in - industrial / heavy equipment",
            "Custom thickness"};

    // Fence height choices (feet). The last label is the "Custom" entry.
    static final double[] HEIGHTS = {4, 5, 6, 8, 10};
    static final String[] HEIGHT_LABELS = {"4 ft", "5 ft", "6 ft", "8 ft", "10 ft", "Custom"};

    // Mesh gauges: GAUGE_KEYS are used to build price keys in the CSV (e.g. fence_6ft_11.5ga).
    static final String[] GAUGE_KEYS = {"9", "11", "11.5"};
    static final String[] GAUGE_LABELS = {"9-gauge (heavy)", "11-gauge (standard)", "11.5-gauge (light)"};

    // Post types: POST_KEYS are used to build price keys (e.g. post_aluminum_line).
    static final String[] POST_KEYS = {"galvanized", "aluminum", "schedule40"};
    static final String[] POST_LABELS = {"Galvanized steel (standard)", "Aluminum (lightweight)", "Schedule 40 (heavy)"};

    static final String[] TOPS = {"None", "Barbed wire", "Razor wire coil", "Privacy slats"};

    /** A concrete pad preset. A length of 0 means "Custom (User Input)". */
    record PadPreset(String name, double length, double width, String use) {
        // toString() is what the combo box displays in its drop-down list.
        @Override public String toString() {
            return length <= 0 ? name : name + "  (" + fmt(length) + "' x " + fmt(width) + "')  -  " + use;
        }
    }

    /**
     * A fencing preset. A perimeter of 0 means "Custom (User Input)".
     * gateOnly = true is the "single gate run" preset (a gate opening with posts, no full perimeter).
     */
    record FencePreset(String name, String enclosure, String use, double perimeter, double height,
                       String top, int strands, boolean gateOnly) {
        @Override public String toString() {
            return perimeter <= 0 ? name
                    : name + "  (" + enclosure + ", " + fmt(perimeter) + " ft, " + fmt(height) + " ft high)";
        }
    }

    // STEP C - Preset tables: the quick-select menus. Picking a preset only fills in the fields;
    // the user can still change any value afterward. A length/perimeter of 0 marks the "Custom" row.
    static final PadPreset[] PAD_PRESETS = {
            new PadPreset("Small Residential Pad", 20, 20, "AC unit / shed base"),
            new PadPreset("Garage / Workshop Pad", 20, 40, "2-car garage"),
            new PadPreset("Small Warehouse Slab", 50, 100, "Small warehouse / storage"),
            new PadPreset("Mid Warehouse Slab", 100, 150, "Mid-size warehouse"),
            new PadPreset("Large Warehouse Slab", 150, 200, "Large warehouse / distribution"),
            new PadPreset("Commercial Loading Apron", 60, 80, "Truck / loading dock apron"),
            new PadPreset("Parking Lot Section", 100, 200, "Commercial parking"),
            new PadPreset("Equipment Pad", 10, 10, "Generator / HVAC unit"),
            new PadPreset("Dumpster Pad", 12, 20, "Commercial waste enclosure"),
            new PadPreset("RV / Boat Storage Slab", 14, 40, "RV / boat storage"),
            new PadPreset("Basketball / Sport Court", 50, 84, "Sport court"),
            new PadPreset("Custom (User Input)", 0, 0, "Any custom size")};

    static final FencePreset[] FENCE_PRESETS = {
            new FencePreset("Small Yard Enclosure", "20' x 40'", "Residential / pet", 120, 4, "None", 1, false),
            new FencePreset("Garage / Shop Perimeter", "20' x 40'", "Workshop security", 120, 6, "None", 1, false),
            new FencePreset("Small Warehouse Perimeter", "50' x 100'", "Small facility", 300, 6, "None", 1, false),
            new FencePreset("Mid Warehouse Perimeter", "100' x 150'", "Commercial facility", 500, 8, "None", 1, false),
            new FencePreset("Large Facility Perimeter", "150' x 200'", "Large warehouse", 700, 8, "None", 1, false),
            new FencePreset("Security Compound", "200' x 300'", "High-security / industrial", 1000, 10, "None", 1, false),
            new FencePreset("Dumpster / Waste Enclosure", "12' x 20'", "Waste enclosure", 64, 6, "None", 1, false),
            new FencePreset("Equipment Yard (barbed top)", "100' x 100'", "Equipment yard", 400, 8, "Barbed wire", 3, false),
            new FencePreset("Parking Lot Perimeter", "100' x 200'", "Parking lot", 600, 4, "None", 1, false),
            new FencePreset("Single Gate Run (gate + posts only)", "gate opening", "Gate opening, posts only", 12, 6, "None", 1, true),
            new FencePreset("Custom (User Input)", "", "Any configuration", 0, 0, "None", 1, false)};

    /** Rounds to exactly two decimal places (half-up) so all money/quantity math is accurate to the cent. */
    static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** Compact number text: 20.0 -> "20", 12.5 -> "12.5". Used for dimensions typed into fields. */
    static String fmt(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    /** 1234.5 -> "1,234.50" (for the printed report). */
    static String num2(double v) { return String.format(Locale.US, "%,.2f", v); }

    /** 1234.5 -> "$1,234.50"; negative amounts print as "-$1,234.50" (used for discounts). */
    static String money(double v) { return v < 0 ? "-$" + num2(-v) : "$" + num2(v); }

    /** 1234.5 -> "1234.50" (no commas - used in CSV files and in the rate fields). */
    static String plain2(double v) { return String.format(Locale.US, "%.2f", v); }

    /** Convenience for building a List of CSV rows. */
    static List<String[]> rows(String[]... r) { return new ArrayList<>(Arrays.asList(r)); }

    /** Lenient number parse used only by the live preset summaries: returns null if the text isn't a valid number. */
    static Double tryNum(JTextField f) {
        String s = f.getText().trim().replace(",", "");
        return DEC.matcher(s).matches() ? Double.valueOf(s) : null;
    }

    /** Runs the given action whenever the text of a field changes (typing, paste, setText...). */
    static void onTextChange(JTextField f, Runnable r) {
        f.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { r.run(); }
            @Override public void removeUpdate(DocumentEvent e) { r.run(); }
            @Override public void changedUpdate(DocumentEvent e) { r.run(); }
        });
    }

    // STEP D - CSV helper: small methods to escape, split (parse), read, write, and append CSV lines.
    // Every file the program stores (prices, labor rates, saved estimates, hours log) goes through here.
    static final class Csv {
        /** Escapes one value for CSV: wraps in quotes if it has a comma or quote; flattens line breaks. */
        static String esc(String s) {
            if (s == null) return "";
            s = s.replace("\r", " ").replace("\n", " ");
            if (s.contains(",") || s.contains("\"")) return "\"" + s.replace("\"", "\"\"") + "\"";
            return s;
        }

        /** Joins values into one CSV line. */
        static String join(Collection<String> f) {
            StringBuilder sb = new StringBuilder();
            for (String x : f) { if (sb.length() > 0) sb.append(','); sb.append(esc(x)); }
            return sb.toString();
        }

        /** Splits one CSV line into fields, honoring quotes and doubled quotes. */
        static String[] parse(String line) {
            List<String> out = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            boolean q = false; // are we inside a quoted section?
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (q) {
                    if (c == '"') {
                        if (i + 1 < line.length() && line.charAt(i + 1) == '"') { sb.append('"'); i++; }
                        else q = false;
                    } else sb.append(c);
                } else if (c == '"') q = true;
                else if (c == ',') { out.add(sb.toString()); sb.setLength(0); }
                else sb.append(c);
            }
            out.add(sb.toString());
            return out.toArray(new String[0]);
        }

        /** Reads a whole CSV file (header row included). A missing file returns an empty list. */
        static List<String[]> read(Path p) {
            List<String[]> rows = new ArrayList<>();
            if (!Files.exists(p)) return rows;
            try {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    if (rows.isEmpty() && line.startsWith("\uFEFF")) line = line.substring(1); // strip Excel BOM
                    if (line.isBlank()) continue;
                    rows.add(parse(line));
                }
            } catch (IOException ex) { throw new UncheckedIOException(ex); }
            return rows;
        }

        /** Overwrites a CSV file with the given rows. */
        static void write(Path p, List<String[]> rows) throws IOException {
            StringBuilder sb = new StringBuilder();
            for (String[] r : rows) sb.append(join(Arrays.asList(r))).append("\n");
            Files.writeString(p, sb, StandardCharsets.UTF_8);
        }

        /** Appends one line to a CSV file (creating it if needed). */
        static void append(Path p, String line) throws IOException {
            Files.writeString(p, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    // STEP E - DataStore: holds unit prices and hourly labor rates in memory.
    //   1) load() starts from the built-in defaults, 2) then overlays whatever is in the CSV files,
    //   so editing a price in the CSV (or the Rates & Prices tab) changes every later estimate.
    static final class DataStore {
        final Map<String, Double> prices = new LinkedHashMap<>(); // key -> unit price / factor
        final Map<String, Double> rates = new LinkedHashMap<>();  // trade -> hourly rate

        static void add(List<String[]> d, String key, String desc, double v) {
            d.add(new String[]{key, desc, plain2(v)});
        }

        /** Default prices written to material_prices.csv the first time the program runs. EDIT THESE to match real costs. */
        static List<String[]> defaultPrices() {
            List<String[]> d = new ArrayList<>();
            add(d, "concrete_per_cy", "Ready-mix concrete ($ per cubic yard)", 165);
            add(d, "mesh_per_sqft", "Wire mesh reinforcement ($ per sq ft of pad)", 0.55);
            add(d, "rebar_per_sqft", "Rebar grid #4 @ 18in O.C. ($ per sq ft of pad)", 1.35);
            add(d, "concrete_sqft_per_labor_hr", "Productivity: pad sq ft placed/finished per labor-hour", 25);

            // Chain-link fabric price per LF for every height x gauge combination (11-gauge is the base price).
            double[] base = {3.50, 4.25, 5.00, 7.25, 9.50};
            double[] gaugeFactor = {1.35, 1.00, 0.85}; // heavy costs more, light costs less
            for (int i = 0; i < HEIGHTS.length; i++)
                for (int j = 0; j < GAUGE_KEYS.length; j++)
                    add(d, "fence_" + (int) HEIGHTS[i] + "ft_" + GAUGE_KEYS[j] + "ga",
                            "Chain-link fabric " + (int) HEIGHTS[i] + " ft, " + GAUGE_LABELS[j] + " ($ per LF)",
                            round2(base[i] * gaugeFactor[j]));

            // Post prices by type (line posts vs. terminal posts), based on a 6 ft fence.
            double[] line = {22, 30, 38}, term = {34, 46, 58};
            for (int k = 0; k < POST_KEYS.length; k++) {
                add(d, "post_" + POST_KEYS[k] + "_line", "Line post, " + POST_LABELS[k] + " (each, 6 ft fence baseline)", line[k]);
                add(d, "post_" + POST_KEYS[k] + "_terminal", "Terminal post (corner/end/gate), " + POST_LABELS[k] + " (each, 6 ft fence baseline)", term[k]);
            }
            add(d, "top_rail_per_lf", "Top rail ($ per LF)", 2.10);
            add(d, "post_concrete_bag", "Concrete for posts (60 lb bag, each)", 6.75);
            add(d, "hardware_pct", "Tension bars, caps, ties & hardware (% of fabric+post+rail cost)", 12);
            add(d, "gate_walk", "Single walk gate, 3-4 ft (each, incl. hardware)", 185);
            add(d, "gate_double", "Double drive gate, 12-16 ft (each, incl. hardware)", 520);
            add(d, "gate_sliding_per_ft", "Sliding gate ($ per ft of opening, incl. hardware)", 95);
            add(d, "gate_sliding_motor", "Sliding gate motor operator (each)", 1850);
            add(d, "barbed_per_strand_lf", "Barbed wire ($ per LF per strand)", 0.40);
            add(d, "razor_per_lf", "Razor wire coil ($ per LF)", 3.50);
            add(d, "privacy_slats_per_lf", "Privacy slats ($ per LF at 6 ft height; scaled by height)", 3.25);
            add(d, "fence_lf_per_labor_hr", "Productivity: LF of fence installed per labor-hour", 6);
            add(d, "gate_labor_hrs_walk", "Labor hours to install one walk gate", 2);
            add(d, "gate_labor_hrs_double", "Labor hours to install one double drive gate", 4);
            add(d, "gate_labor_hrs_sliding", "Labor hours to install one sliding gate", 8);
            return d;
        }

        /** Default hourly labor rates (one row per trade). */
        static List<String[]> defaultRates() {
            return rows(new String[]{CONCRETE_TRADE, "38.00"}, new String[]{FENCE_TRADE, "34.00"});
        }

        /** Creates the data folder and any missing CSV files, then loads the values into memory. */
        void init() throws IOException {
            Files.createDirectories(DATA_DIR);
            if (!Files.exists(PRICES_CSV)) {
                List<String[]> r = rows(new String[]{"key", "description", "unit_price"});
                r.addAll(defaultPrices());
                Csv.write(PRICES_CSV, r);
            }
            if (!Files.exists(LABOR_CSV)) {
                List<String[]> r = rows(new String[]{"trade", "hourly_rate"});
                r.addAll(defaultRates());
                Csv.write(LABOR_CSV, r);
            }
            if (!Files.exists(HOURS_CSV))
                Csv.write(HOURS_CSV, rows(new String[]{"logged_at", "estimate_date", "project", "trade",
                        "employees", "hours_per_employee", "total_hours", "hourly_rate", "labor_cost"}));
            load();
        }

        /** (Re)loads prices and rates: defaults first, then values from the CSV files override them. */
        void load() {
            prices.clear();
            for (String[] d : defaultPrices()) prices.put(d[0], Double.parseDouble(d[2]));
            overlay(PRICES_CSV, 2, prices);
            rates.clear();
            for (String[] d : defaultRates()) rates.put(d[0], Double.parseDouble(d[1]));
            overlay(LABOR_CSV, 1, rates);
        }

        /** Copies "key -> numeric value in column col" from a CSV into the map (skips the header and bad numbers). */
        static void overlay(Path p, int col, Map<String, Double> m) {
            List<String[]> rows = Csv.read(p);
            for (int i = 1; i < rows.size(); i++) {
                String[] r = rows.get(i);
                if (r.length > col && DEC.matcher(r[col].trim()).matches())
                    m.put(r[0].trim(), Double.parseDouble(r[col].trim()));
            }
        }

        double price(String key) {
            Double v = prices.get(key);
            if (v == null) throw new IllegalStateException("Missing price in material_prices.csv: " + key);
            return v;
        }

        double rate(String trade) { return rates.getOrDefault(trade, 0.0); }
    }

    /** Gate selections for the fence: quantity and width for each of the three gate types. */
    static final class Gates {
        int wq, dq, sq;          // quantities: walk, double drive, sliding
        double ww, dw, sw;       // widths (ft)
        boolean motor;           // motor operator on sliding gate(s)
        double opening() { return wq * ww + dq * dw + sq * sw; } // total gate opening length
        int count() { return wq + dq + sq; }
    }

    /** Everything about one estimate: the inputs and every calculated result. Filled in by buildEstimate(). */
    static final class Est {
        // --- project info ---
        String project, client, location, estimator, date, notes = "";

        // --- concrete pad: size, volume, costs ---
        String padPreset, reinfType = "None", equipDesc = "";
        double length, width, area, thickIn, wastePct, rawCY, adjCY;
        double concretePrice, concreteCost, reinfPrice, reinfCost, equipCost;

        // --- concrete labor ---
        int cEmp;
        double cHrs, cTotalHrs, cRate, cLabor;

        // --- concrete pad subtotal and discounts ---
        double padSub;                       // subtotal BEFORE discount
        double padDiscPct, padDisc;          // percentage discount (rate and dollar amount)
        double padFixedDisc;                 // fixed-dollar discount
        double padDiscTotal, padTotal;       // total discount, and total AFTER discount

        // --- fence: layout and quantities ---
        boolean fence;                       // true when the fence option is included
        String fPreset = "", fHeightLabel = "", fGauge = "", fPost = "", fTop = "None";
        boolean closed;
        double perimeter, height, spacing, bagsPerPost, gateOpen, fabricLF, railLF;
        double fOveragePct, orderLF;         // material overage % and the resulting ordered length
        int strands, linePosts, termPosts, totalPosts, bags;
        Gates gates = new Gates();

        // --- fence costs ---
        double fabricPrice, fabricCost, postCost, gateCost, postConcCost, railCost, hardwareCost, topCost, hardwarePct;
        double fMaterials;

        // --- fence labor ---
        int fEmp;
        double fHrs, fTotalHrs, fRate, fLabor;

        // --- fence subtotal and discounts ---
        double fSub;
        double fDiscPct, fDisc;
        double fFixedDisc;
        double fDiscTotal, fTotal;

        // --- project totals ---
        double preDiscountTotal;             // pad subtotal + fence subtotal, before ANY discount
        double sectionsTotal;                // pad total + fence total (after the section discounts)
        double gDiscPct, gPctDisc, gFixedDisc, gDiscTotal; // grand-total discounts
        double discountedTotal;              // total after ALL discounts
        double totalDiscounts;               // sum of every discount given
        double contingencyPct, contingency;  // optional project contingency
        double grandTotal;                   // final price = discountedTotal + contingency
    }

    /** Thrown when an input is invalid. Carries the offending field so the GUI can jump to it. */
    static final class ValidationException extends Exception {
        private static final long serialVersionUID = 1L;
        final JComponent field;
        ValidationException(String msg, JComponent field) { super(msg); this.field = field; }
    }

    final DataStore store = new DataStore();
    final JTabbedPane tabs = new JTabbedPane();

    // --- Tab 1: project information ---
    final JTextField projName = new JTextField(32), client = new JTextField(32), location = new JTextField(32),
            estDate = new JTextField(LocalDate.now().toString(), 12), estimator = new JTextField(32);

    // --- Tab 2: concrete pad ---
    final JComboBox<PadPreset> padPreset = new JComboBox<>(PAD_PRESETS);
    final JLabel padSummary = new JLabel();
    final JTextField padL = new JTextField("20", 8), padW = new JTextField("20", 8);
    final JComboBox<String> thickCombo = new JComboBox<>(THICKNESS_LABELS);
    final JTextField thickCustom = new JTextField("7", 6);
    final JTextField waste = new JTextField("8", 6);
    final JComboBox<String> reinfCombo = new JComboBox<>(new String[]{"None", "Wire mesh", "Rebar grid"});
    final JCheckBox equipChk = new JCheckBox("Include equipment rental");
    final JTextField equipDesc = new JTextField("Concrete pump / screed / saw", 24), equipCost = new JTextField("0", 10);
    final JTextField cEmp = new JTextField("4", 6), cHrs = new JTextField("8", 6), cRate = new JTextField(10);
    final JTextField cDisc = new JTextField("0", 6), cFixedDisc = new JTextField("0", 8);

    // --- Tab 3: chain-link fence ---
    final JCheckBox fenceChk = new JCheckBox("Include chain-link fencing in this estimate");
    final JComboBox<FencePreset> fPreset = new JComboBox<>(FENCE_PRESETS);
    final JLabel fenceSummary = new JLabel();
    final JTextField fPerim = new JTextField("120", 8);
    final JCheckBox fClosed = new JCheckBox("Closed perimeter (4 corner posts) - uncheck for an open run (2 end posts)", true);
    final JComboBox<String> fHeight = new JComboBox<>(HEIGHT_LABELS);
    final JTextField fHeightCustom = new JTextField("7", 6);
    final JComboBox<String> fGauge = new JComboBox<>(GAUGE_LABELS);
    final JTextField fSpacing = new JTextField("10", 6);
    final JComboBox<String> fPost = new JComboBox<>(POST_LABELS);
    final JTextField fBags = new JTextField("1", 6);
    final JTextField fOverage = new JTextField("5", 6);
    final JTextField gWalkQ = new JTextField("1", 4), gWalkW = new JTextField("4", 5),
            gDblQ = new JTextField("0", 4), gDblW = new JTextField("12", 5),
            gSlideQ = new JTextField("0", 4), gSlideW = new JTextField("20", 5);
    final JCheckBox gMotor = new JCheckBox("Sliding gate motor operator");
    final JComboBox<String> fTop = new JComboBox<>(TOPS);
    final JComboBox<String> fStrands = new JComboBox<>(new String[]{"1", "2", "3"});
    final JTextField fEmp = new JTextField("3", 6), fHrs = new JTextField("8", 6), fRate = new JTextField(10);
    final JTextField fDisc = new JTextField("0", 6), fFixedDisc = new JTextField("0", 8);
    JPanel fenceForm; // the form panel, kept so all its fields can be greyed out when fencing is excluded

    // --- Tab 5: grand-total discount, contingency, notes ---
    final JTextField gDiscPct = new JTextField("0", 6), gFixedDisc = new JTextField("0", 8);
    final JTextField contingencyPct = new JTextField("0", 6);
    final JTextArea notesArea = new JTextArea(8, 50);

    // --- Tab 6 / 7: output and history ---
    final JTextArea out = new JTextArea();
    JScrollPane outScroll;
    CsvTablePanel histEstimates, histHours;
    JComponent helpPane;   // the Help tab (kept so the welcome screen can jump straight to it)
    JComponent homePane;   // the Home tab (kept so the header bar and buttons can jump back to it)
    final List<Runnable> productRefreshers = new ArrayList<>(); // re-read prices into the product cards

    ConcreteFenceEstimator() {
        super("Concrete Pad & Chain-Link Fence Estimator");

        // Create the data folder / default CSV files and load prices. If that fails, fall back to built-in defaults.
        try {
            store.init();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not create data files in " + DATA_DIR.toAbsolutePath()
                    + "\n" + ex.getMessage() + "\nDefault prices will be used.", "Data folder error", JOptionPane.ERROR_MESSAGE);
            store.load();
        }
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1280, 900);  // larger window so the bigger text and buttons fit comfortably
        setLocationRelativeTo(null);

        out.setEditable(false);
        out.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 17)); // monospaced + large so report columns line up and are easy to read
        out.setText("Fill in the tabs, then click \"Calculate Estimate\".");
        outScroll = new JScrollPane(out);

        notesArea.setLineWrap(true);
        notesArea.setWrapStyleWord(true);

        // Add all the tabs. Each tab component is a direct child of the JTabbedPane (needed by showError()).
        // CONSTRUCTOR STEP 2 - Build the tabs and add them to the tabbed pane, in the order the user works through them.
        homePane = homeTab();
        tabs.addTab("Home", homePane);
        tabs.addTab("1. Project Info", projectTab());
        tabs.addTab("2. Concrete Pad", padTab());
        tabs.addTab("3. Chain-Link Fence (optional)", fenceTab());
        tabs.addTab("4. Rates & Prices", ratesTab());
        tabs.addTab("5. Discounts, Contingency & Notes", extrasTab());
        tabs.addTab("6. Estimate Output", outScroll);
        tabs.addTab("7. History / Hours Log", historyTab());
        helpPane = helpTab();
        tabs.addTab("8. Help - How This Program Works", helpPane);

        // Bottom button bar.
        // CONSTRUCTOR STEP 3 - Bottom button bar. Each button just calls one handler method (onCalculate, onSave, ...).
        JButton calc = new JButton("Calculate Estimate");
        JButton save = new JButton("Save Estimate to CSV");
        JButton export = new JButton("Export Report (.txt / .csv)");
        JButton print = new JButton("Print Report");
        JButton help = new JButton("Help");
        calc.addActionListener(a -> onCalculate());
        save.addActionListener(a -> onSave());
        export.addActionListener(a -> onExport());
        print.addActionListener(a -> onPrint());
        help.addActionListener(a -> tabs.setSelectedComponent(helpPane));
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        bottom.add(calc); bottom.add(save); bottom.add(export); bottom.add(print); bottom.add(help);

        setLayout(new BorderLayout());
        add(headerBar(), BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        // CONSTRUCTOR STEP 4 - Connect the listeners, load the labor rates into their fields, then apply the first
        // preset in each menu so the screen starts out consistent.
        wireListeners();
        refreshRateFields();
        // Apply the first preset in each menu so the fields and summaries start out consistent.
        padPreset.setSelectedIndex(0);
        fPreset.setSelectedIndex(0);
        applyPadPreset();
        applyFencePreset();
        updateToggles();
    }

    /** A two-column label/field layout helper so every tab is built the same way. */
    static final class Form extends JPanel {
        private static final long serialVersionUID = 1L;
        int row = 0;
        Form() {
            super(new GridBagLayout());
            setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        }
        /** Bold section heading with an underline. */
        void header(String t) {
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0; g.gridy = row++; g.gridwidth = 2; g.fill = GridBagConstraints.HORIZONTAL;
            g.insets = new Insets(14, 0, 6, 0);
            JLabel l = new JLabel(t);
            l.setFont(l.getFont().deriveFont(Font.BOLD, 14f));
            l.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.GRAY));
            add(l, g);
        }
        /** A label on the left and one or more components (left-aligned) on the right. */
        void row(String label, JComponent... cs) {
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0; g.gridy = row; g.anchor = GridBagConstraints.WEST; g.insets = new Insets(3, 2, 3, 10);
            add(new JLabel(label), g);
            JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            for (JComponent c : cs) p.add(c);
            g.gridx = 1; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.insets = new Insets(3, 0, 3, 0);
            add(p, g);
            row++;
        }
        /** A component that spans the full width of the form (used for summary boxes and the notes box). */
        void wide(JComponent c) {
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0; g.gridy = row++; g.gridwidth = 2; g.fill = GridBagConstraints.HORIZONTAL;
            g.weightx = 1; g.insets = new Insets(3, 2, 3, 2);
            add(c, g);
        }
        /** A grey one-line help note. */
        void note(String t) {
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0; g.gridy = row++; g.gridwidth = 2; g.anchor = GridBagConstraints.WEST;
            g.insets = new Insets(2, 2, 2, 2);
            JLabel l = new JLabel(t);
            l.setForeground(Color.DARK_GRAY);
            add(l, g);
        }
        /** Pushes everything to the top of the panel. */
        void finish() {
            GridBagConstraints g = new GridBagConstraints();
            g.gridx = 0; g.gridy = row; g.weighty = 1;
            add(new JLabel(), g);
        }
    }

    /** Wraps a summary label in a titled box (used for the preset summaries). */
    static JPanel summaryBox(String title, JLabel label) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder(title));
        label.setBorder(BorderFactory.createEmptyBorder(2, 6, 4, 6));
        p.add(label, BorderLayout.CENTER);
        return p;
    }

    /** Tab 1: project name, client, location, date, estimator. */
    JScrollPane projectTab() {
        Form f = new Form();
        f.header("Project details (all fields required)");
        f.row("Project name", projName);
        f.row("Client name", client);
        f.row("Project location / address", location);
        f.row("Estimate date (YYYY-MM-DD)", estDate);
        f.row("Estimator name", estimator);
        f.note(" ");
        f.note("Next: choose the pad size on tab 2, optionally add fencing on tab 3, set discounts/contingency/notes on tab 5,");
        f.note("then click \"Calculate Estimate\".");
        f.finish();
        return new JScrollPane(f);
    }

    /** Tab 2: pad preset, dimensions, thickness, waste, add-ons, labor, discount. */
    JScrollPane padTab() {
        Form f = new Form();
        f.header("Pad size");
        f.row("Preset (quick select)", padPreset);
        f.wide(summaryBox("Selected preset summary", padSummary));
        f.note("Pick a preset to fill in the size, then change Length / Width below if the job is different.");
        f.row("Length (ft)", padL);
        f.row("Width (ft)", padW);

        f.header("Slab thickness & waste");
        f.row("Thickness", thickCombo, thickCustom, new JLabel("in (custom only)"));
        f.row("Concrete waste percentage", waste, new JLabel("% (allowed 5-15%, default 8%)"));

        f.header("Optional add-ons");
        f.row("Rebar / wire mesh", reinfCombo);
        f.row("Equipment rental", equipChk);
        f.row("   Description", equipDesc);
        f.row("   Total rental cost ($)", equipCost);

        f.header("Labor - concrete crew");
        f.row("Number of employees", cEmp);
        JButton sug = new JButton("Suggest hours");
        sug.addActionListener(a -> suggestConcreteHours());
        f.row("Hours per employee", cHrs, sug);
        f.row("Hourly labor rate ($)", cRate, new JLabel("(default from labor_rates.csv - edit permanently on tab 4)"));

        f.header("Discount on concrete pad subtotal (6.2)");
        f.row("Percentage discount", cDisc, new JLabel("%  (e.g. 5 or 10)"));
        f.row("Fixed dollar discount", cFixedDisc, new JLabel("$  (applied after the percentage discount)"));
        f.finish();
        return new JScrollPane(f);
    }

    /** Tab 3: the optional fence module. */
    JComponent fenceTab() {
        Form f = new Form();
        fenceForm = f;
        f.header("Layout");
        f.row("Preset (quick select)", fPreset);
        f.wide(summaryBox("Selected preset summary", fenceSummary));
        f.note("Pick a preset to fill in the perimeter, height and top treatment, then override any field below.");
        f.row("Perimeter / run length (ft)", fPerim);
        f.row("Layout", fClosed);

        f.header("Fence materials");
        f.row("Fence height", fHeight, fHeightCustom, new JLabel("ft (custom only)"));
        f.row("Mesh gauge", fGauge);
        f.row("Post spacing (ft on center)", fSpacing);
        f.row("Post type", fPost);
        f.row("Concrete bags (60 lb) per post", fBags);
        f.row("Material overage", fOverage, new JLabel("% extra fabric / rail / top treatment (default 5%)"));

        f.header("Gates (each opening is deducted from the fabric length)");
        f.row("Walk gate (3-4 ft wide)", new JLabel("Qty"), gWalkQ, new JLabel("Width (ft)"), gWalkW);
        f.row("Double drive gate (12-16 ft)", new JLabel("Qty"), gDblQ, new JLabel("Width (ft)"), gDblW);
        f.row("Sliding gate (12-40 ft)", new JLabel("Qty"), gSlideQ, new JLabel("Width (ft)"), gSlideW);
        f.row("", gMotor);

        f.header("Top treatment");
        f.row("Treatment", fTop);
        f.row("Barbed wire strands", fStrands);

        f.header("Labor - fence crew");
        f.row("Number of employees", fEmp);
        JButton sug = new JButton("Suggest hours");
        sug.addActionListener(a -> suggestFenceHours());
        f.row("Hours per employee", fHrs, sug);
        f.row("Hourly labor rate ($)", fRate, new JLabel("(default from labor_rates.csv)"));

        f.header("Discount on fencing subtotal (6.2)");
        f.row("Percentage discount", fDisc, new JLabel("%  (e.g. 5 or 10)"));
        f.row("Fixed dollar discount", fFixedDisc, new JLabel("$  (applied after the percentage discount)"));
        f.finish();

        // The "include fencing" checkbox sits ABOVE the form so it stays enabled when the form is greyed out.
        JPanel p = new JPanel(new BorderLayout());
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 8));
        fenceChk.setFont(fenceChk.getFont().deriveFont(Font.BOLD));
        top.add(fenceChk);
        p.add(top, BorderLayout.NORTH);
        p.add(new JScrollPane(f), BorderLayout.CENTER);
        return p;
    }

    /** Tab 4: editable material price table and labor rate table (both stored in CSV). */
    JComponent ratesTab() {
        // After either table is saved, reload prices/rates into memory and refresh the labor-rate fields.
        Runnable onSaved = () -> { store.load(); refreshRateFields(); refreshProducts(); };
        CsvTablePanel prices = new CsvTablePanel("Material unit prices & productivity factors", PRICES_CSV, 2, onSaved);
        CsvTablePanel labor = new CsvTablePanel("Labor rates by trade ($/hour)", LABOR_CSV, 1, onSaved);
        JSplitPane sp = new JSplitPane(JSplitPane.VERTICAL_SPLIT, prices, labor);
        sp.setResizeWeight(0.75);
        return sp;
    }

    /** Tab 5: grand-total discount, project contingency and job notes. */
    JScrollPane extrasTab() {
        Form f = new Form();
        f.header("Grand-total discount (6.2)");
        f.row("Percentage discount", gDiscPct, new JLabel("%  of the combined pad + fence total"));
        f.row("Fixed dollar discount", gFixedDisc, new JLabel("$  (applied after the percentage discount)"));
        f.note("Section discounts for the pad and the fence are entered on tabs 2 and 3. All discounts can be combined.");

        f.header("Project contingency (6.3)");
        f.row("Contingency percentage", contingencyPct, new JLabel("%  added to the grand total (0 = none)"));
        f.note("Contingency is calculated on the total AFTER all discounts and added at the end.");

        f.header("Job notes (6.4) - printed at the bottom of the estimate report");
        f.wide(new JScrollPane(notesArea));
        f.finish();
        return new JScrollPane(f);
    }

    /** Tab 7: read-only views of the saved estimates and the work-hours log (retrieved from CSV). */
    JComponent historyTab() {
        histEstimates = new CsvTablePanel("Saved estimates", ESTIMATES_CSV, -1, null);
        histHours = new CsvTablePanel("Manpower / work-hours log", HOURS_CSV, -1, null);
        JSplitPane sp = new JSplitPane(JSplitPane.VERTICAL_SPLIT, histEstimates, histHours);
        sp.setResizeWeight(0.5);
        return sp;
    }

    /** Hooks up all the event listeners (preset selection, enabling/disabling fields, live summaries). */
    void wireListeners() {
        // Choosing a preset fills in the fields; the user can still edit any field afterwards.
        padPreset.addActionListener(a -> applyPadPreset());
        fPreset.addActionListener(a -> applyFencePreset());

        // Enable/disable dependent fields.
        thickCombo.addActionListener(a -> updateToggles());
        fHeight.addActionListener(a -> updateToggles());
        fTop.addActionListener(a -> updateToggles());
        equipChk.addActionListener(a -> updateToggles());
        fenceChk.addActionListener(a -> updateToggles());

        // Keep the preset summaries live: they flag when the user has overridden the preset values.
        onTextChange(padL, this::updatePadSummary);
        onTextChange(padW, this::updatePadSummary);
        onTextChange(fPerim, this::updateFenceSummary);
        onTextChange(fHeightCustom, this::updateFenceSummary);
        fHeight.addActionListener(a -> updateFenceSummary());
    }

    /** Recursively enables/disables a component and everything inside it. */
    static void setEnabledRec(Component c, boolean en) {
        c.setEnabled(en);
        if (c instanceof Container ct) for (Component k : ct.getComponents()) setEnabledRec(k, en);
    }

    /** Applies the on/off rules: fence form only when fencing is included, custom boxes only when "Custom" is picked, etc. */
    void updateToggles() {
        setEnabledRec(fenceForm, fenceChk.isSelected());
        boolean on = fenceChk.isSelected();
        fHeightCustom.setEnabled(on && fHeight.getSelectedIndex() == HEIGHTS.length);
        fStrands.setEnabled(on && "Barbed wire".equals(fTop.getSelectedItem()));
        thickCustom.setEnabled(thickCombo.getSelectedIndex() == THICKNESS.length);
        equipDesc.setEnabled(equipChk.isSelected());
        equipCost.setEnabled(equipChk.isSelected());
    }

    /** Copies the selected pad preset's size into the fields (Custom leaves the fields alone) and refreshes the summary. */
    void applyPadPreset() {
        PadPreset p = (PadPreset) padPreset.getSelectedItem();
        if (p != null && p.length() > 0) { padL.setText(fmt(p.length())); padW.setText(fmt(p.width())); }
        else padL.requestFocusInWindow();
        updatePadSummary();
    }

    /** Copies the selected fence preset into the fields (perimeter, height, top treatment, default gates). */
    void applyFencePreset() {
        FencePreset p = (FencePreset) fPreset.getSelectedItem();
        if (p == null || p.perimeter() <= 0) { updateFenceSummary(); return; } // Custom: leave fields alone
        fPerim.setText(fmt(p.perimeter()));
        fClosed.setSelected(!p.gateOnly());
        // Select the matching height in the menu, or use "Custom" if the preset height isn't a standard one.
        int idx = HEIGHTS.length;
        for (int i = 0; i < HEIGHTS.length; i++) if (HEIGHTS[i] == p.height()) idx = i;
        fHeight.setSelectedIndex(idx);
        if (idx == HEIGHTS.length) fHeightCustom.setText(fmt(p.height()));
        fTop.setSelectedItem(p.top());
        fStrands.setSelectedItem(String.valueOf(p.strands()));
        // Default gates: one walk gate for normal layouts; one 12 ft drive gate for the "single gate run".
        if (p.gateOnly()) { gWalkQ.setText("0"); gDblQ.setText("1"); gDblW.setText("12"); }
        else { gWalkQ.setText("1"); gWalkW.setText("4"); gDblQ.setText("0"); }
        gSlideQ.setText("0");
        updateToggles();
        updateFenceSummary();
    }

    /** Rebuilds the pad preset summary box: preset details, current entry, and an "overridden" flag if they differ. */
    void updatePadSummary() {
        PadPreset p = (PadPreset) padPreset.getSelectedItem();
        StringBuilder h = new StringBuilder("<html>");
        boolean custom = p == null || p.length() <= 0;
        if (custom) {
            h.append("<b>Custom size</b> - type your own length and width below.");
        } else {
            h.append("<b>").append(p.name()).append("</b> - typical use: ").append(p.use()).append("<br>");
            h.append("Preset size: ").append(fmt(p.length())).append("' x ").append(fmt(p.width()))
                    .append("' = ").append(num2(p.length() * p.width())).append(" sq ft");
        }
        Double l = tryNum(padL), w = tryNum(padW);
        if (l != null && w != null) {
            h.append("<br>Current entry: ").append(fmt(l)).append("' x ").append(fmt(w)).append("' = ")
                    .append(num2(l * w)).append(" sq ft");
            if (!custom && (l != p.length() || w != p.width()))
                h.append(" <font color='#B45F06'><b>(overridden from preset)</b></font>");
        } else {
            h.append("<br><font color='#CC0000'>Length and width must be valid positive numbers.</font>");
        }
        padSummary.setText(h.append("</html>").toString());
    }

    /** Rebuilds the fence preset summary box: preset details, current perimeter/height, and an "overridden" flag. */
    void updateFenceSummary() {
        FencePreset p = (FencePreset) fPreset.getSelectedItem();
        StringBuilder h = new StringBuilder("<html>");
        boolean custom = p == null || p.perimeter() <= 0;
        if (custom) {
            h.append("<b>Custom layout</b> - enter your own perimeter / run length and height below.");
        } else {
            h.append("<b>").append(p.name()).append("</b> - typical use: ").append(p.use()).append("<br>");
            h.append("Preset: ").append(p.gateOnly() ? "gate opening only" : "enclosure " + p.enclosure())
                    .append(", ").append(fmt(p.perimeter())).append(" ft, ").append(fmt(p.height())).append(" ft high");
            if (!"None".equals(p.top()))
                h.append(", ").append(p.top()).append("Barbed wire".equals(p.top()) ? " (" + p.strands() + " strands)" : "");
        }
        Double perim = tryNum(fPerim);
        Double ht = currentFenceHeight();
        if (perim != null && ht != null) {
            h.append("<br>Current entry: ").append(fmt(perim)).append(" ft, ").append(fmt(ht)).append(" ft high");
            if (!custom && (perim != p.perimeter() || ht != p.height()))
                h.append(" <font color='#B45F06'><b>(overridden from preset)</b></font>");
        } else {
            h.append("<br><font color='#CC0000'>Perimeter and height must be valid numbers.</font>");
        }
        fenceSummary.setText(h.append("</html>").toString());
    }

    /** Height currently selected in the fence height menu (or typed as custom); null if the custom text is invalid. */
    Double currentFenceHeight() {
        int hi = fHeight.getSelectedIndex();
        if (hi >= 0 && hi < HEIGHTS.length) return HEIGHTS[hi];
        return tryNum(fHeightCustom);
    }

    /** Puts the labor rates from labor_rates.csv into the two rate fields. */
    void refreshRateFields() {
        cRate.setText(plain2(store.rate(CONCRETE_TRADE)));
        fRate.setText(plain2(store.rate(FENCE_TRADE)));
    }

    /** A required, non-empty text field. */
    String req(JTextField f, String name) throws ValidationException {
        String s = f.getText().trim();
        if (s.isEmpty()) throw new ValidationException(name + " is required.", f);
        return s;
    }

    /** A decimal number >= 0 (or > 0 if allowZero is false). Negative or non-numeric text is rejected. */
    double num(JTextField f, String name, boolean allowZero) throws ValidationException {
        String s = f.getText().trim().replace(",", "");
        if (s.isEmpty()) throw new ValidationException(name + " is required.", f);
        if (s.startsWith("-")) throw new ValidationException(name + " cannot be negative.", f);
        if (!DEC.matcher(s).matches())
            throw new ValidationException(name + " must be a number (digits only, e.g. 12 or 12.5). You entered: \"" + s + "\"", f);
        double v = Double.parseDouble(s);
        if (Double.isInfinite(v)) throw new ValidationException(name + " is too large.", f);
        if (!allowZero && v <= 0) throw new ValidationException(name + " must be greater than zero.", f);
        return v;
    }

    /** A whole number (quantities, employee counts). */
    int whole(JTextField f, String name, boolean allowZero) throws ValidationException {
        String s = f.getText().trim();
        if (s.isEmpty()) throw new ValidationException(name + " is required.", f);
        if (s.startsWith("-")) throw new ValidationException(name + " cannot be negative.", f);
        if (!INT.matcher(s).matches())
            throw new ValidationException(name + " must be a whole number. You entered: \"" + s + "\"", f);
        int v = Integer.parseInt(s);
        if (!allowZero && v == 0) throw new ValidationException(name + " must be at least 1.", f);
        return v;
    }

    /** A percentage between 0 and 100. */
    double percent(JTextField f, String name) throws ValidationException {
        double v = num(f, name, true);
        if (v > 100) throw new ValidationException(name + " cannot be more than 100.", f);
        return v;
    }

    /** A number that must fall between min and max (used for gate widths). */
    double ranged(JTextField f, String name, double min, double max) throws ValidationException {
        double v = num(f, name, false);
        if (v < min || v > max)
            throw new ValidationException(name + " must be between " + fmt(min) + " and " + fmt(max) + " ft.", f);
        return v;
    }

    /** Reads and validates the gate quantity/width fields (a width is only checked when that gate's quantity > 0). */
    Gates readGates() throws ValidationException {
        Gates g = new Gates();
        g.wq = whole(gWalkQ, "Walk gate quantity", true);
        if (g.wq > 0) g.ww = ranged(gWalkW, "Walk gate width", 3, 4);
        g.dq = whole(gDblQ, "Double drive gate quantity", true);
        if (g.dq > 0) g.dw = ranged(gDblW, "Double drive gate width", 12, 16);
        g.sq = whole(gSlideQ, "Sliding gate quantity", true);
        if (g.sq > 0) g.sw = ranged(gSlideW, "Sliding gate width", 12, 40);
        g.motor = gMotor.isSelected();
        return g;
    }

    /** Suggests hours per employee for the pad: area / (sq ft per labor-hour), split across the crew, rounded up to 0.5 hr. */
    void suggestConcreteHours() {
        try {
            double area = num(padL, "Pad length", false) * num(padW, "Pad width", false);
            int emp = whole(cEmp, "Number of employees", false);
            double total = area / store.price("concrete_sqft_per_labor_hr");
            double per = Math.max(0.5, Math.ceil(total / emp * 2) / 2.0);
            cHrs.setText(fmt(per));
        } catch (ValidationException ex) { showError(ex.getMessage(), ex.field); }
    }

    /** Suggests hours per employee for the fence: fabric LF / (LF per labor-hour) plus fixed hours for each gate. */
    void suggestFenceHours() {
        try {
            double perim = num(fPerim, "Perimeter / run length", false);
            Gates g = readGates();
            int emp = whole(fEmp, "Number of employees", false);
            double lf = Math.max(0, perim - g.opening());
            double total = lf / store.price("fence_lf_per_labor_hr")
                    + g.wq * store.price("gate_labor_hrs_walk")
                    + g.dq * store.price("gate_labor_hrs_double")
                    + g.sq * store.price("gate_labor_hrs_sliding");
            double per = Math.max(0.5, Math.ceil(total / emp * 2) / 2.0);
            fHrs.setText(fmt(per));
        } catch (ValidationException ex) { showError(ex.getMessage(), ex.field); }
    }

    /**
     * Applies a percentage discount and then a fixed-dollar discount to a subtotal.
     * Returns {percentage discount $, fixed discount $}. The fixed discount may not exceed what is left
     * after the percentage discount (so a total can never go negative).
     */
    double[] discounts(double base, double pct, double fixed, JComponent fixedField, String what) throws ValidationException {
        double pctAmt = round2(base * pct / 100.0);
        double remaining = round2(base - pctAmt);
        if (fixed > remaining + 1e-9)
            throw new ValidationException("The fixed dollar discount (" + money(fixed) + ") for " + what
                    + " is more than the amount left after the percentage discount (" + money(remaining) + ").", fixedField);
        return new double[]{pctAmt, round2(fixed)};
    }

    /**
     * Reads every input field, validates it, and performs ALL calculations.
     * Returns a fully filled-in Est, or throws ValidationException pointing at the first bad field.
     */
    Est buildEstimate() throws ValidationException {
        // buildEstimate() flow: (1) project info -> (2) pad size & volume -> (3) add-ons -> (4) concrete labor ->
        // (5) pad subtotal & discount -> (6) fence (optional) -> (7) project totals, grand discount, contingency.
        // If any field is invalid a ValidationException is thrown and the GUI jumps to that field.
        Est e = new Est();

        // ---- project info ----
        // STEP 1 - Project info: all five fields are required; the date must be a real YYYY-MM-DD date.
        e.project = req(projName, "Project name");
        e.client = req(client, "Client name");
        e.location = req(location, "Project location / address");
        e.estimator = req(estimator, "Estimator name");
        try {
            e.date = LocalDate.parse(req(estDate, "Estimate date")).toString();
        } catch (DateTimeParseException ex) {
            throw new ValidationException("Estimate date must be a valid date in YYYY-MM-DD format.", estDate);
        }
        e.notes = notesArea.getText().trim(); // job notes (free text, optional)

        // ---- pad dimensions and concrete volume ----
        // STEP 2 - Pad size: read length/width (must be > 0), then area = length x width.
        PadPreset pp = (PadPreset) padPreset.getSelectedItem();
        e.padPreset = pp == null ? "Custom" : pp.name();
        e.length = num(padL, "Pad length", false);
        e.width = num(padW, "Pad width", false);
        e.area = round2(e.length * e.width);
        int ti = thickCombo.getSelectedIndex();
        // Thickness comes from the menu (4/5/6/8 in) or from the custom box when "Custom thickness" is chosen.
        e.thickIn = ti < THICKNESS.length ? THICKNESS[ti] : num(thickCustom, "Custom thickness (in)", false);

        // Waste percentage is read and must be inside the allowed 5%-15% range.
        e.wastePct = num(waste, "Waste percentage", false);
        if (e.wastePct < WASTE_MIN || e.wastePct > WASTE_MAX)
            throw new ValidationException("Concrete waste percentage must be between " + fmt(WASTE_MIN) + "% and "
                    + fmt(WASTE_MAX) + "%.", waste);

        // STEP 3 - Concrete volume and cost:
        //   a) raw cubic yards = length x width x (thickness in inches / 12) / 27   [27 cubic feet = 1 cubic yard]
        //   b) waste-adjusted cubic yards = raw x (1 + waste% / 100)
        //   c) concrete cost = adjusted cubic yards x price per cubic yard (from material_prices.csv)
        double rawExact = e.length * e.width * (e.thickIn / 12.0) / 27.0;          // cubic yards = L x W x T(ft) / 27
        e.rawCY = round2(rawExact);
        e.adjCY = round2(rawExact * (1 + e.wastePct / 100.0));
        e.concretePrice = store.price("concrete_per_cy");
        e.concreteCost = round2(e.adjCY * e.concretePrice);

        // ---- optional add-ons: reinforcement and equipment ----
        // STEP 4 - Optional add-ons: wire mesh or rebar is priced per sq ft of pad (area x price);
        // equipment rental is a flat dollar amount typed by the user.
        e.reinfType = (String) reinfCombo.getSelectedItem();
        if ("Wire mesh".equals(e.reinfType)) { e.reinfPrice = store.price("mesh_per_sqft"); e.reinfCost = round2(e.area * e.reinfPrice); }
        else if ("Rebar grid".equals(e.reinfType)) { e.reinfPrice = store.price("rebar_per_sqft"); e.reinfCost = round2(e.area * e.reinfPrice); }
        if (equipChk.isSelected()) {
            e.equipDesc = equipDesc.getText().trim();
            e.equipCost = round2(num(equipCost, "Equipment rental cost", true));
        }

        // ---- concrete labor ----
        // STEP 5 - Concrete labor: total hours = employees x hours per employee; labor cost = total hours x hourly rate.
        e.cEmp = whole(cEmp, "Number of concrete employees", false);
        e.cHrs = num(cHrs, "Concrete hours per employee", false);
        e.cRate = num(cRate, "Concrete hourly labor rate", true);
        e.cTotalHrs = round2(e.cEmp * e.cHrs);
        e.cLabor = round2(e.cTotalHrs * e.cRate);

        // ---- pad subtotal and discounts ----
        // STEP 6 - Pad subtotal = concrete + reinforcement + equipment + labor.
        // Then the percentage discount is applied, then the fixed-dollar discount; pad total = subtotal - discounts.
        e.padSub = round2(e.concreteCost + e.reinfCost + e.equipCost + e.cLabor);
        e.padDiscPct = percent(cDisc, "Concrete discount %");
        double padFixedIn = num(cFixedDisc, "Concrete fixed dollar discount", true);
        double[] pd = discounts(e.padSub, e.padDiscPct, padFixedIn, cFixedDisc, "the concrete pad");
        e.padDisc = pd[0];
        e.padFixedDisc = pd[1];
        e.padDiscTotal = round2(e.padDisc + e.padFixedDisc);
        e.padTotal = round2(e.padSub - e.padDiscTotal);

        // ---- 4. fencing (only if the option is selected) ----
        // STEP 7 - Fencing is only calculated when the "Include chain-link fencing" box is checked.
        e.fence = fenceChk.isSelected();
        if (e.fence) buildFence(e);

        // ---- project totals ----
        // Step 1: combine the pad and fence totals (each already net of its own section discounts).
        e.preDiscountTotal = round2(e.padSub + (e.fence ? e.fSub : 0));
        e.sectionsTotal = round2(e.padTotal + (e.fence ? e.fTotal : 0));

        // Step 2: grand-total discounts (percentage first, then fixed dollar).
        e.gDiscPct = percent(gDiscPct, "Grand-total discount %");
        double gFixedIn = num(gFixedDisc, "Grand-total fixed dollar discount", true);
        double[] gd = discounts(e.sectionsTotal, e.gDiscPct, gFixedIn, gFixedDisc, "the grand total");
        e.gPctDisc = gd[0];
        e.gFixedDisc = gd[1];
        e.gDiscTotal = round2(e.gPctDisc + e.gFixedDisc);
        e.discountedTotal = round2(e.sectionsTotal - e.gDiscTotal);
        e.totalDiscounts = round2(e.padDiscTotal + (e.fence ? e.fDiscTotal : 0) + e.gDiscTotal);

        // Step 3: optional project contingency, calculated on the discounted total and added at the end.
        e.contingencyPct = percent(contingencyPct, "Contingency %");
        e.contingency = round2(e.discountedTotal * e.contingencyPct / 100.0);
        e.grandTotal = round2(e.discountedTotal + e.contingency);
        return e;
    }

    /** Fence quantity, cost and labor calculations. Fills the fence fields of the Est. */
    void buildFence(Est e) throws ValidationException {
        // FENCE STEP 1 - Read the layout: preset name, perimeter / run length, closed or open, height (menu or custom),
        // mesh gauge, post spacing, post type, concrete bags per post, and material overage %.
        FencePreset fp = (FencePreset) fPreset.getSelectedItem();
        e.fPreset = fp == null ? "Custom" : fp.name();
        e.perimeter = num(fPerim, "Fence perimeter / run length", false);
        e.closed = fClosed.isSelected();

        // Height: from the menu, or the custom box.
        int hi = fHeight.getSelectedIndex();
        e.height = hi < HEIGHTS.length ? HEIGHTS[hi] : num(fHeightCustom, "Custom fence height (ft)", false);
        if (e.height > 20) throw new ValidationException("Fence height must be 20 ft or less.", fHeightCustom);
        e.fHeightLabel = fmt(e.height) + " ft";
        int gi = fGauge.getSelectedIndex();
        e.fGauge = GAUGE_LABELS[gi];
        e.spacing = num(fSpacing, "Post spacing", false);
        int pi = fPost.getSelectedIndex();
        e.fPost = POST_LABELS[pi];
        e.bagsPerPost = num(fBags, "Concrete bags per post", true);
        e.fOveragePct = percent(fOverage, "Fencing material overage %");

        // Gates and the total opening that is deducted from the fabric run.
        // FENCE STEP 2 - Gates: read quantity and width for each gate type, add them up as the total gate opening,
        // and make sure the openings are not longer than the perimeter.
        e.gates = readGates();
        e.gateOpen = e.gates.opening();
        if (e.gateOpen > e.perimeter + 1e-9)
            throw new ValidationException("Gate openings (" + fmt(e.gateOpen) + " ft) exceed the fence perimeter / run length ("
                    + fmt(e.perimeter) + " ft).", fPerim);
        e.fTop = (String) fTop.getSelectedItem();
        e.strands = Integer.parseInt((String) fStrands.getSelectedItem());

        // ---- quantities ----
        // FENCE STEP 3 - Quantities:
        //   fabric LF = perimeter - gate openings;  line posts = (fabric LF / spacing) - 1;
        //   terminal posts = corners or ends + 2 per gate;  concrete bags = total posts x bags per post;
        //   top rail LF = fabric LF;  ordered LF = fabric LF x (1 + overage%).
        e.fabricLF = round2(e.perimeter - e.gateOpen);
        // Line posts = (LF / spacing) - 1, rounded up so the last span is covered; never negative.
        e.linePosts = e.fabricLF > 0 ? Math.max(0, (int) Math.ceil(e.fabricLF / e.spacing) - 1) : 0;
        // Terminal posts: corners (closed) or ends (open run) + 2 posts for every gate. A gate-only run has just the gate posts.
        e.termPosts = (e.fabricLF > 0 ? (e.closed ? 4 : 2) : 0) + 2 * e.gates.count();
        e.totalPosts = e.linePosts + e.termPosts;
        e.bags = (int) Math.ceil(e.totalPosts * e.bagsPerPost);
        e.railLF = e.fabricLF;
        // Overage: continuous materials (fabric, rail, top treatment) are ORDERED with extra for cuts and waste.
        // Posts, gates and concrete bags are counted exactly, so overage is not applied to them.
        e.orderLF = round2(e.fabricLF * (1 + e.fOveragePct / 100.0));

        // ---- costs ----
        // Fabric price depends on height and gauge. Non-standard heights are scaled from the 6 ft price.
        // FENCE STEP 4 - Material costs. Price keys are built from the menu choices (height + gauge, post type),
        // then looked up in material_prices.csv. Each cost = quantity x unit price.
        String gk = GAUGE_KEYS[gi];
        e.fabricPrice = hi < HEIGHTS.length
                ? store.price("fence_" + (int) HEIGHTS[hi] + "ft_" + gk + "ga")
                : store.price("fence_6ft_" + gk + "ga") / 6.0 * e.height;
        e.fabricCost = round2(e.orderLF * e.fabricPrice);

        // Post price scales with post length (fence height + 2.5 ft embedded) relative to the 6 ft baseline.
        double scale = (e.height + 2.5) / 8.5;
        String pk = POST_KEYS[pi];
        e.postCost = round2(e.linePosts * store.price("post_" + pk + "_line") * scale
                + e.termPosts * store.price("post_" + pk + "_terminal") * scale);

        // Gates: walk and double gates are priced per unit; sliding gates per foot of opening plus an optional motor.
        Gates g = e.gates;
        e.gateCost = round2(g.wq * store.price("gate_walk") + g.dq * store.price("gate_double")
                + g.sq * (g.sw * store.price("gate_sliding_per_ft") + (g.motor ? store.price("gate_sliding_motor") : 0)));

        e.postConcCost = round2(e.bags * store.price("post_concrete_bag"));
        e.railCost = round2(e.orderLF * store.price("top_rail_per_lf"));
        // Tension bars, caps, ties and other hardware are a % of fabric + post + rail cost.
        e.hardwarePct = store.price("hardware_pct");
        e.hardwareCost = round2((e.fabricCost + e.postCost + e.railCost) * e.hardwarePct / 100.0);

        // Optional top treatment.
        // Top treatment cost depends on the choice: barbed wire (per strand per LF), razor coil (per LF), or privacy slats (scaled by height).
        switch (e.fTop) {
            case "Barbed wire" -> e.topCost = round2(e.orderLF * e.strands * store.price("barbed_per_strand_lf"));
            case "Razor wire coil" -> e.topCost = round2(e.orderLF * store.price("razor_per_lf"));
            case "Privacy slats" -> e.topCost = round2(e.orderLF * store.price("privacy_slats_per_lf") * e.height / 6.0);
            default -> e.topCost = 0;
        }
        // Fence materials total = fabric + posts + gates + post concrete + top rail + hardware + top treatment.
        e.fMaterials = round2(e.fabricCost + e.postCost + e.gateCost + e.postConcCost + e.railCost + e.hardwareCost + e.topCost);

        // ---- fence labor ----
        // FENCE STEP 5 - Fence labor: total hours = employees x hours per employee; labor cost = total hours x rate.
        e.fEmp = whole(fEmp, "Number of fence employees", false);
        e.fHrs = num(fHrs, "Fence hours per employee", false);
        e.fRate = num(fRate, "Fence hourly labor rate", true);
        e.fTotalHrs = round2(e.fEmp * e.fHrs);
        e.fLabor = round2(e.fTotalHrs * e.fRate);

        // ---- fence subtotal and discounts ----
        // FENCE STEP 6 - Fence subtotal = materials + labor, then percentage and fixed discounts give the fence total.
        e.fSub = round2(e.fMaterials + e.fLabor);
        e.fDiscPct = percent(fDisc, "Fencing discount %");
        double fFixedIn = num(fFixedDisc, "Fencing fixed dollar discount", true);
        double[] fd = discounts(e.fSub, e.fDiscPct, fFixedIn, fFixedDisc, "the fencing");
        e.fDisc = fd[0];
        e.fFixedDisc = fd[1];
        e.fDiscTotal = round2(e.fDisc + e.fFixedDisc);
        e.fTotal = round2(e.fSub - e.fDiscTotal);
    }

    /** Section banner. */
    static void sec(StringBuilder s, String t) {
        s.append("\n").append("-".repeat(72)).append("\n  ").append(t).append("\n").append("-".repeat(72)).append("\n");
    }
    /** A "label ........ text" line. */
    static void txt(StringBuilder s, String l, String v) { s.append(String.format("  %-34s %s\n", l, v)); }
    /** A "label ........ $amount" line with the amount right-aligned. */
    static void mon(StringBuilder s, String l, double v) { s.append(String.format("  %-54s %14s\n", l, money(v))); }
    /** An amount line preceded by a rule, used for subtotals and totals. */
    static void monBold(StringBuilder s, String l, double v) {
        s.append(String.format("  %-54s %14s\n", "", "--------------"));
        s.append(String.format("  %-54s %14s\n", l, money(v)));
    }
    /** An indented sub-heading inside a section. */
    static void sub(StringBuilder s, String t) { s.append("\n  ").append(t).append("\n"); }

    /** Word-wraps free text (the job notes) to the report width. */
    static void wrap(StringBuilder s, String text, int width) {
        for (String para : text.split("\\R")) {
            StringBuilder line = new StringBuilder("  ");
            for (String w : para.trim().split("\\s+")) {
                if (line.length() + w.length() + 1 > width && line.length() > 2) { s.append(line).append("\n"); line = new StringBuilder("  "); }
                if (line.length() > 2) line.append(' ');
                line.append(w);
            }
            s.append(line).append("\n");
        }
    }

    /**
     * Builds the full text report. Sections:
     * Project Info, Concrete Pad Summary, Fencing Summary (if selected), Cost Breakdown, Discounts, Grand Total, Job Notes.
     */
    String report(Est e) {
        // report() builds the printable estimate as one long String, section by section:
        // Project Info -> Concrete Pad Summary -> Fencing Summary (if selected) -> Cost Breakdown -> Discounts -> Grand Total -> Notes.
        // The helpers sec(), txt(), mon(), monBold() keep the columns lined up.
        StringBuilder s = new StringBuilder();
        String bar = "=".repeat(72);
        s.append(bar).append("\n   CONCRETE PAD & CHAIN-LINK FENCE ESTIMATE\n").append(bar).append("\n");

        // ---- Project Info ----
        sec(s, "PROJECT INFORMATION");
        txt(s, "Project name", e.project);
        txt(s, "Client", e.client);
        txt(s, "Location / address", e.location);
        txt(s, "Estimate date", e.date);
        txt(s, "Estimator", e.estimator);

        // ---- Concrete Pad Summary ----
        sec(s, "CONCRETE PAD SUMMARY");
        txt(s, "Pad preset", e.padPreset);
        txt(s, "Length x width", fmt(e.length) + " ft x " + fmt(e.width) + " ft");
        txt(s, "Total area", num2(e.area) + " sq ft");
        txt(s, "Slab thickness", fmt(e.thickIn) + " in");
        txt(s, "Raw concrete volume", num2(e.rawCY) + " cubic yards");
        txt(s, "Waste allowance", num2(e.wastePct) + " %");
        txt(s, "Waste-adjusted volume", num2(e.adjCY) + " cubic yards");
        txt(s, "Rebar / wire mesh", e.reinfType);
        txt(s, "Equipment rental", e.equipCost > 0 || !e.equipDesc.isEmpty()
                ? (e.equipDesc.isEmpty() ? "included" : e.equipDesc) : "not included");
        txt(s, "Crew", e.cEmp + " employees x " + num2(e.cHrs) + " hrs = " + num2(e.cTotalHrs) + " labor hours");
        txt(s, "Hourly labor rate", money(e.cRate));

        // ---- Fencing Summary (only if selected) ----
        if (e.fence) {
            Gates g = e.gates;
            sec(s, "CHAIN-LINK FENCING SUMMARY");
            txt(s, "Fence preset", e.fPreset);
            txt(s, "Perimeter / run length", num2(e.perimeter) + " ft (" + (e.closed ? "closed perimeter" : "open run") + ")");
            txt(s, "Height / mesh", e.fHeightLabel + ", " + e.fGauge);
            txt(s, "Post type / spacing", e.fPost + ", " + fmt(e.spacing) + " ft O.C.");
            txt(s, "Walk gates", g.wq == 0 ? "none" : g.wq + " x " + fmt(g.ww) + " ft");
            txt(s, "Double drive gates", g.dq == 0 ? "none" : g.dq + " x " + fmt(g.dw) + " ft");
            txt(s, "Sliding gates", g.sq == 0 ? "none" : g.sq + " x " + fmt(g.sw) + " ft" + (g.motor ? " (with motor)" : ""));
            txt(s, "Total gate openings", num2(e.gateOpen) + " ft");
            txt(s, "Fence fabric installed", num2(e.fabricLF) + " LF");
            txt(s, "Material overage", num2(e.fOveragePct) + " %  ->  " + num2(e.orderLF) + " LF ordered (fabric, rail, top)");
            txt(s, "Line posts", String.valueOf(e.linePosts));
            txt(s, "Terminal posts (corner/end/gate)", String.valueOf(e.termPosts));
            txt(s, "Total posts", String.valueOf(e.totalPosts));
            txt(s, "Post concrete", e.bags + " x 60 lb bags (" + fmt(e.bagsPerPost) + " per post)");
            txt(s, "Top treatment", "Barbed wire".equals(e.fTop) ? e.fTop + " (" + e.strands + " strands)" : e.fTop);
            txt(s, "Crew", e.fEmp + " employees x " + num2(e.fHrs) + " hrs = " + num2(e.fTotalHrs) + " labor hours");
            txt(s, "Hourly labor rate", money(e.fRate));
        }

        // ---- Cost Breakdown (all amounts BEFORE discounts) ----
        sec(s, "COST BREAKDOWN (before discounts)");
        sub(s, "Concrete pad");
        mon(s, "Concrete (" + num2(e.adjCY) + " CY @ " + money(e.concretePrice) + "/CY)", e.concreteCost);
        if (!"None".equals(e.reinfType))
            mon(s, e.reinfType + " (" + num2(e.area) + " sq ft @ " + money(e.reinfPrice) + ")", e.reinfCost);
        if (e.equipCost > 0 || !e.equipDesc.isEmpty())
            mon(s, "Equipment rental" + (e.equipDesc.isEmpty() ? "" : " (" + e.equipDesc + ")"), e.equipCost);
        mon(s, "Labor (" + num2(e.cTotalHrs) + " hrs @ " + money(e.cRate) + ")", e.cLabor);
        monBold(s, "Concrete pad subtotal", e.padSub);

        if (e.fence) {
            sub(s, "Chain-link fence");
            mon(s, "Fabric (" + num2(e.orderLF) + " LF @ " + money(e.fabricPrice) + "/LF)", e.fabricCost);
            mon(s, "Posts (" + e.totalPosts + ")", e.postCost);
            mon(s, "Gates", e.gateCost);
            mon(s, "Post concrete (" + e.bags + " bags)", e.postConcCost);
            mon(s, "Top rail (" + num2(e.orderLF) + " LF)", e.railCost);
            mon(s, "Tension bars, caps, ties & hardware (" + num2(e.hardwarePct) + " %)", e.hardwareCost);
            mon(s, "Top treatment", e.topCost);
            monBold(s, "Fence materials subtotal", e.fMaterials);
            mon(s, "Labor (" + num2(e.fTotalHrs) + " hrs @ " + money(e.fRate) + ")", e.fLabor);
            monBold(s, "Fencing subtotal", e.fSub);
        }
        s.append("\n");
        monBold(s, "PROJECT SUBTOTAL BEFORE ANY DISCOUNTS", e.preDiscountTotal);

        // ---- Discounts (pre- and post-discount shown for every level) ----
        sec(s, "DISCOUNTS");
        sub(s, "Concrete pad");
        mon(s, "Subtotal before discount", e.padSub);
        mon(s, "Percentage discount (" + num2(e.padDiscPct) + " %)", -e.padDisc);
        mon(s, "Fixed dollar discount", -e.padFixedDisc);
        mon(s, "Concrete pad total after discount", e.padTotal);
        if (e.fence) {
            sub(s, "Chain-link fence");
            mon(s, "Subtotal before discount", e.fSub);
            mon(s, "Percentage discount (" + num2(e.fDiscPct) + " %)", -e.fDisc);
            mon(s, "Fixed dollar discount", -e.fFixedDisc);
            mon(s, "Fence total after discount", e.fTotal);
        }
        sub(s, "Grand-total discount");
        mon(s, "Combined total before grand-total discount", e.sectionsTotal);
        mon(s, "Percentage discount (" + num2(e.gDiscPct) + " %)", -e.gPctDisc);
        mon(s, "Fixed dollar discount", -e.gFixedDisc);
        mon(s, "Total after all discounts", e.discountedTotal);
        s.append("\n");
        mon(s, "Total of all discounts given", -e.totalDiscounts);

        // ---- Grand Total ----
        sec(s, "GRAND TOTAL");
        mon(s, "Project total BEFORE discounts", e.preDiscountTotal);
        mon(s, "Project total AFTER discounts", e.discountedTotal);
        mon(s, "Project contingency (" + num2(e.contingencyPct) + " % of discounted total)", e.contingency);
        s.append("  ").append("=".repeat(70)).append("\n");
        s.append(String.format("  %-54s %14s\n", "GRAND TOTAL", money(e.grandTotal)));
        s.append("  ").append("=".repeat(70)).append("\n");

        // ---- Job notes ----
        sec(s, "JOB NOTES");
        if (e.notes.isEmpty()) s.append("  (none)\n");
        else wrap(s, e.notes, 70);
        return s.toString();
    }

    /** Converts an estimate into one ordered "column name -> value" record (this becomes one row of estimates.csv). */
    Map<String, String> csvRecord(Est e) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("saved_at", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        m.put("estimate_date", e.date);
        m.put("project", e.project);
        m.put("client", e.client);
        m.put("location", e.location);
        m.put("estimator", e.estimator);
        // pad
        m.put("pad_preset", e.padPreset);
        m.put("length_ft", plain2(e.length));
        m.put("width_ft", plain2(e.width));
        m.put("area_sqft", plain2(e.area));
        m.put("thickness_in", plain2(e.thickIn));
        m.put("waste_pct", plain2(e.wastePct));
        m.put("raw_cy", plain2(e.rawCY));
        m.put("adjusted_cy", plain2(e.adjCY));
        m.put("concrete_cost", plain2(e.concreteCost));
        m.put("reinforcement", e.reinfType);
        m.put("reinforcement_cost", plain2(e.reinfCost));
        m.put("equipment_cost", plain2(e.equipCost));
        m.put("concrete_employees", String.valueOf(e.cEmp));
        m.put("concrete_hours_per_employee", plain2(e.cHrs));
        m.put("concrete_total_hours", plain2(e.cTotalHrs));
        m.put("concrete_labor_rate", plain2(e.cRate));
        m.put("concrete_labor_cost", plain2(e.cLabor));
        m.put("pad_subtotal", plain2(e.padSub));
        m.put("pad_discount_pct", plain2(e.padDiscPct));
        m.put("pad_discount_pct_amount", plain2(e.padDisc));
        m.put("pad_discount_fixed", plain2(e.padFixedDisc));
        m.put("pad_total", plain2(e.padTotal));
        // fence
        m.put("fence_included", e.fence ? "YES" : "NO");
        m.put("fence_preset", e.fPreset);
        m.put("fence_perimeter_ft", plain2(e.perimeter));
        m.put("fence_height_ft", plain2(e.height));
        m.put("fence_gauge", e.fGauge);
        m.put("post_type", e.fPost);
        m.put("gate_openings_ft", plain2(e.gateOpen));
        m.put("fabric_lf", plain2(e.fabricLF));
        m.put("fence_overage_pct", plain2(e.fOveragePct));
        m.put("fence_order_lf", plain2(e.orderLF));
        m.put("line_posts", String.valueOf(e.linePosts));
        m.put("terminal_posts", String.valueOf(e.termPosts));
        m.put("post_concrete_bags", String.valueOf(e.bags));
        m.put("top_treatment", e.fence ? e.fTop : "");
        m.put("fence_fabric_cost", plain2(e.fabricCost));
        m.put("fence_post_cost", plain2(e.postCost));
        m.put("fence_gate_cost", plain2(e.gateCost));
        m.put("fence_post_concrete_cost", plain2(e.postConcCost));
        m.put("fence_top_rail_cost", plain2(e.railCost));
        m.put("fence_hardware_cost", plain2(e.hardwareCost));
        m.put("fence_top_treatment_cost", plain2(e.topCost));
        m.put("fence_materials_subtotal", plain2(e.fMaterials));
        m.put("fence_employees", String.valueOf(e.fEmp));
        m.put("fence_hours_per_employee", plain2(e.fHrs));
        m.put("fence_total_hours", plain2(e.fTotalHrs));
        m.put("fence_labor_rate", plain2(e.fRate));
        m.put("fence_labor_cost", plain2(e.fLabor));
        m.put("fence_subtotal", plain2(e.fSub));
        m.put("fence_discount_pct", plain2(e.fDiscPct));
        m.put("fence_discount_pct_amount", plain2(e.fDisc));
        m.put("fence_discount_fixed", plain2(e.fFixedDisc));
        m.put("fence_total", plain2(e.fTotal));
        // project totals
        m.put("subtotal_before_discounts", plain2(e.preDiscountTotal));
        m.put("grand_discount_pct", plain2(e.gDiscPct));
        m.put("grand_discount_pct_amount", plain2(e.gPctDisc));
        m.put("grand_discount_fixed", plain2(e.gFixedDisc));
        m.put("total_discounts", plain2(e.totalDiscounts));
        m.put("total_after_discounts", plain2(e.discountedTotal));
        m.put("contingency_pct", plain2(e.contingencyPct));
        m.put("contingency_amount", plain2(e.contingency));
        m.put("grand_total", plain2(e.grandTotal));
        m.put("notes", e.notes);
        return m;
    }

    /** Saves the estimate to estimates.csv and logs the manpower hours to work_hours_log.csv. */
    void saveEstimate(Est e) throws IOException {
        Files.createDirectories(DATA_DIR);
        Map<String, String> rec = csvRecord(e);
        // SAVE STEP 1 - csvRecord(e) makes one row of named columns; the column names become the CSV header line.
        // SAVE STEP 2 - Write the header only when estimates.csv is new (or set an old-format file aside), then append the row.
        // SAVE STEP 3 - Append one row per crew (concrete, and fence if included) to work_hours_log.csv.
        String header = Csv.join(rec.keySet());

        // If an older estimates.csv exists with different columns (older program version), set it aside
        // so the columns of the new file always match their header.
        if (Files.exists(ESTIMATES_CSV) && Files.size(ESTIMATES_CSV) > 0) {
            String first;
            try (BufferedReader br = Files.newBufferedReader(ESTIMATES_CSV, StandardCharsets.UTF_8)) { first = br.readLine(); }
            if (first != null && first.startsWith("\uFEFF")) first = first.substring(1);
            if (!header.equals(first)) {
                String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                Files.move(ESTIMATES_CSV, DATA_DIR.resolve("estimates_old_" + stamp + ".csv"));
            }
        }
        if (!Files.exists(ESTIMATES_CSV) || Files.size(ESTIMATES_CSV) == 0) Csv.append(ESTIMATES_CSV, header);
        Csv.append(ESTIMATES_CSV, Csv.join(rec.values()));

        // Work-hours log: one row for the concrete crew and (if included) one for the fence crew.
        String now = rec.get("saved_at");
        if (!Files.exists(HOURS_CSV) || Files.size(HOURS_CSV) == 0)
            Csv.append(HOURS_CSV, "logged_at,estimate_date,project,trade,employees,hours_per_employee,total_hours,hourly_rate,labor_cost");
        Csv.append(HOURS_CSV, Csv.join(Arrays.asList(now, e.date, e.project, CONCRETE_TRADE, String.valueOf(e.cEmp),
                plain2(e.cHrs), plain2(e.cTotalHrs), plain2(e.cRate), plain2(e.cLabor))));
        if (e.fence)
            Csv.append(HOURS_CSV, Csv.join(Arrays.asList(now, e.date, e.project, FENCE_TRADE, String.valueOf(e.fEmp),
                    plain2(e.fHrs), plain2(e.fTotalHrs), plain2(e.fRate), plain2(e.fLabor))));

        // Refresh the History tab tables.
        if (histEstimates != null) histEstimates.reload();
        if (histHours != null) histHours.reload();
    }

    /** Shows a validation message, switches to the tab holding the bad field, and focuses/selects that field. */
    void showError(String msg, JComponent field) {
        if (field != null) {
            Component c = field;
            while (c != null && c.getParent() != tabs) c = c.getParent(); // find the tab that contains the field
            if (c != null) tabs.setSelectedComponent(c);
            field.requestFocusInWindow();
            if (field instanceof JTextField tf) tf.selectAll();
        }
        JOptionPane.showMessageDialog(this, msg, "Please check your input", JOptionPane.WARNING_MESSAGE);
    }

    /** "Calculate Estimate": validate, calculate, show the report on tab 6 and echo it to the console. */
    void onCalculate() {
        try {
            // CALCULATE BUTTON: (1) buildEstimate() validates and calculates, (2) report(e) formats the text,
            // (3) the text is shown on the Output tab and echoed to the console. A bad field shows a message instead.
            Est e = buildEstimate();
            String text = report(e);
            out.setText(text);
            out.setCaretPosition(0);
            System.out.println(text);
            tabs.setSelectedComponent(outScroll);
        } catch (ValidationException ex) {
            showError(ex.getMessage(), ex.field);
        }
    }

    /** "Save Estimate to CSV": calculate, show the report, and append the estimate + hours to the CSV files. */
    void onSave() {
        try {
            // SAVE BUTTON: same calculation as above, then saveEstimate(e) writes the CSV records.
            Est e = buildEstimate();
            out.setText(report(e));
            out.setCaretPosition(0);
            saveEstimate(e);
            tabs.setSelectedComponent(outScroll);
            JOptionPane.showMessageDialog(this, "Estimate saved.\n\nEstimates: " + ESTIMATES_CSV.toAbsolutePath()
                    + "\nWork-hours log: " + HOURS_CSV.toAbsolutePath(), "Saved", JOptionPane.INFORMATION_MESSAGE);
        } catch (ValidationException ex) {
            showError(ex.getMessage(), ex.field);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not write CSV file:\n" + ex.getMessage(), "Save error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * "Export Report": writes the report to a file chosen by the user.
     * A file name ending in .csv is written as a CSV record (header + one row); anything else is written as the text report.
     */
    void onExport() {
        try {
            Est e = buildEstimate();
            String text = report(e);
            out.setText(text);
            out.setCaretPosition(0);
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Export report (.txt = printable text, .csv = CSV record)");
            String safe = e.project.replaceAll("[^A-Za-z0-9._-]+", "_");
            fc.setSelectedFile(new java.io.File("Estimate_" + safe + "_" + e.date + ".txt"));
            if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                java.io.File file = fc.getSelectedFile();
                String name = file.getName().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".txt") && !name.endsWith(".csv")) file = new java.io.File(file.getPath() + ".txt");
                if (file.getName().toLowerCase(Locale.ROOT).endsWith(".csv")) {
                    Map<String, String> rec = csvRecord(e);
                    Files.writeString(file.toPath(), Csv.join(rec.keySet()) + "\n" + Csv.join(rec.values()) + "\n", StandardCharsets.UTF_8);
                } else {
                    Files.writeString(file.toPath(), text, StandardCharsets.UTF_8);
                }
                JOptionPane.showMessageDialog(this, "Report exported to:\n" + file.getAbsolutePath());
            }
        } catch (ValidationException ex) {
            showError(ex.getMessage(), ex.field);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not write file:\n" + ex.getMessage(), "Export error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** "Print Report": calculate, then send the report text to the printer dialog. */
    void onPrint() {
        try {
            Est e = buildEstimate();
            out.setText(report(e));
            out.setCaretPosition(0);
            tabs.setSelectedComponent(outScroll);
            out.print();
        } catch (ValidationException ex) {
            showError(ex.getMessage(), ex.field);
        } catch (PrinterException ex) {
            JOptionPane.showMessageDialog(this, "Could not print:\n" + ex.getMessage(), "Print error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Shows a CSV file in a table. If editCol >= 0 that one column can be edited and saved back to the file. */
    static final class CsvTablePanel extends JPanel {
        private static final long serialVersionUID = 1L;
        final Path path;
        final int editCol;          // index of the editable column, or -1 for read-only
        final Runnable onSaved;     // called after a successful save (used to reload prices/rates)
        final DefaultTableModel model;
        final JTable table;

        CsvTablePanel(String title, Path path, int editCol, Runnable onSaved) {
            super(new BorderLayout(4, 4));
            this.path = path;
            this.editCol = editCol;
            this.onSaved = onSaved;
            final int ec = editCol;
            model = new DefaultTableModel() {
                @Override public boolean isCellEditable(int r, int c) { return c == ec; }
            };
            table = new JTable(model);
            table.setFillsViewportHeight(true);
            table.setRowHeight(30);
            table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);

            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
            JLabel l = new JLabel(title + "   [" + path + "]");
            l.setFont(l.getFont().deriveFont(Font.BOLD));
            top.add(l);
            JButton reload = new JButton("Reload");
            reload.addActionListener(a -> reload());
            top.add(reload);
            if (editCol >= 0) {
                JButton save = new JButton("Save changes to CSV");
                save.addActionListener(a -> save());
                top.add(save);
                top.add(new JLabel("(double-click a value to edit)"));
            }
            add(top, BorderLayout.NORTH);
            add(new JScrollPane(table), BorderLayout.CENTER);
            reload();
        }

        /** Reads the CSV (first row = column headers) into the table. */
        void reload() {
            List<String[]> rows = Csv.read(path);
            if (rows.isEmpty()) { model.setDataVector(new Object[0][0], new Object[0]); return; }
            String[] h = rows.get(0);
            Object[][] data = new Object[rows.size() - 1][h.length];
            for (int i = 1; i < rows.size(); i++)
                for (int j = 0; j < h.length; j++)
                    data[i - 1][j] = j < rows.get(i).length ? rows.get(i)[j] : "";
            model.setDataVector(data, h);
            for (int j = 0; j < table.getColumnCount(); j++)
                table.getColumnModel().getColumn(j).setPreferredWidth(j == 1 && editCol == 2 ? 480 : 140);
        }

        /** Validates the edited column (non-negative numbers only) and writes the table back to the CSV. */
        void save() {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            int cols = model.getColumnCount();
            List<String[]> rows = new ArrayList<>();
            String[] h = new String[cols];
            for (int j = 0; j < cols; j++) h[j] = model.getColumnName(j);
            rows.add(h);
            for (int i = 0; i < model.getRowCount(); i++) {
                String[] r = new String[cols];
                for (int j = 0; j < cols; j++) {
                    Object v = model.getValueAt(i, j);
                    r[j] = v == null ? "" : v.toString().trim();
                    if (j == editCol && !DEC.matcher(r[j]).matches()) {
                        JOptionPane.showMessageDialog(this, "Row " + (i + 1) + ": \"" + r[j]
                                + "\" is not valid.\nValues must be non-negative numbers.", "Invalid value", JOptionPane.WARNING_MESSAGE);
                        table.changeSelection(i, j, false, false);
                        return;
                    }
                }
                rows.add(r);
            }
            try {
                Csv.write(path, rows);
                if (onSaved != null) onSaved.run();
                JOptionPane.showMessageDialog(this, "Saved to " + path, "Saved", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Could not save:\n" + ex.getMessage(), "Save error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /** The company blue used by the header bar, the welcome screen and the Home page. */
    static final Color BRAND_BLUE = new Color(0x1F3A5F);

    /** Switches to the tab with the given title (e.g. "Help - ..." is found by its start text). */
    void goToTab(String titleStart) {
        for (int i = 0; i < tabs.getTabCount(); i++)
            if (tabs.getTitleAt(i).startsWith(titleStart)) { tabs.setSelectedIndex(i); return; }
    }

    /**
     * The header bar that stays at the top of the window on every tab.
     * Clicking the company name or the Home button takes the user back to the Home tab.
     */
    JComponent headerBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(BRAND_BLUE);
        bar.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));

        JLabel brand = new JLabel("Welcome to Myra's Concrete Fence Company");
        brand.setForeground(Color.WHITE);
        brand.setFont(brand.getFont().deriveFont(Font.BOLD, 24f));
        brand.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        brand.setToolTipText("Click to return to the Home page");
        brand.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { tabs.setSelectedComponent(homePane); }
        });

        JButton home = new JButton("Home");
        home.setToolTipText("Return to the Home page");
        home.addActionListener(a -> tabs.setSelectedComponent(homePane));

        bar.add(brand, BorderLayout.WEST);
        bar.add(home, BorderLayout.EAST);
        return bar;
    }

    /** The Home tab: welcome message, quick-start buttons and the "What You Can Buy" picture gallery. */
    JComponent homeTab() {
        // ---- welcome banner ----
        JPanel hero = new JPanel(new GridLayout(0, 1, 0, 8));
        hero.setBackground(BRAND_BLUE);
        hero.setBorder(BorderFactory.createEmptyBorder(24, 30, 24, 30));
        JLabel title = new JLabel("Welcome to Myra's Concrete Fence Company", SwingConstants.CENTER);
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 34f));
        JLabel sub = new JLabel("Concrete Pad & Chain-Link Fence Estimator", SwingConstants.CENTER);
        sub.setForeground(new Color(0xD6E4F5));
        sub.setFont(sub.getFont().deriveFont(Font.PLAIN, 22f));
        JLabel hello = new JLabel("Hello, how is your day going.", SwingConstants.CENTER);
        hello.setForeground(Color.WHITE);
        hello.setFont(hello.getFont().deriveFont(Font.ITALIC, 22f));
        hero.add(title);
        hero.add(sub);
        hero.add(hello);

        // ---- quick-start buttons ----
        JButton start = new JButton("Start a New Estimate");
        JButton rates = new JButton("Rates & Prices");
        JButton help = new JButton("Help");
        start.addActionListener(a -> goToTab("1. Project"));
        rates.addActionListener(a -> goToTab("4. Rates"));
        help.addActionListener(a -> tabs.setSelectedComponent(helpPane));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 16, 14));
        buttons.add(start);
        buttons.add(rates);
        buttons.add(help);

        // ---- gallery title ----
        JLabel galleryTitle = new JLabel("What You Can Buy", SwingConstants.CENTER);
        galleryTitle.setFont(galleryTitle.getFont().deriveFont(Font.BOLD, 26f));
        JLabel galleryHint = new JLabel("Current prices come from material_prices.csv (Tab 4). "
                + "Put your own photos in estimator_data/product_images to replace the drawings.", SwingConstants.CENTER);
        galleryHint.setForeground(Color.DARK_GRAY);
        JPanel galleryHead = new JPanel(new GridLayout(0, 1, 0, 4));
        galleryHead.setBorder(BorderFactory.createEmptyBorder(8, 0, 10, 0));
        galleryHead.add(galleryTitle);
        galleryHead.add(galleryHint);

        JPanel top = new JPanel(new BorderLayout());
        top.add(hero, BorderLayout.NORTH);
        top.add(buttons, BorderLayout.CENTER);
        top.add(galleryHead, BorderLayout.SOUTH);

        // ---- product cards (3 per row) ----
        JPanel grid = new JPanel(new GridLayout(0, 3, 18, 18));
        grid.setBorder(BorderFactory.createEmptyBorder(6, 24, 24, 24));
        for (Product pr : PRODUCTS) grid.add(productCard(pr));

        JPanel page = new JPanel(new BorderLayout());
        page.add(top, BorderLayout.NORTH);
        page.add(grid, BorderLayout.CENTER);
        JScrollPane sp = new JScrollPane(page);
        sp.getVerticalScrollBar().setUnitIncrement(24);
        return sp;
    }

    /** One product card: picture, name, short description and the current price. */
    JComponent productCard(Product pr) {
        JLabel name = new JLabel(pr.name());
        name.setFont(name.getFont().deriveFont(Font.BOLD, 18f));
        JLabel blurb = new JLabel("<html><body style='width:260px'>" + pr.blurb() + "</body></html>");
        JLabel price = new JLabel();
        price.setForeground(new Color(0x1B6B35));
        price.setFont(price.getFont().deriveFont(Font.BOLD));
        // The price text is rebuilt whenever prices change (see refreshProducts()).
        Runnable refresh = () -> price.setText(money(store.prices.getOrDefault(pr.priceKey(), 0.0)) + "  " + pr.unit());
        refresh.run();
        productRefreshers.add(refresh);

        // Same text height on every card so all the pictures line up in neat rows.
        JPanel text = new JPanel(new BorderLayout(0, 4));
        text.setBorder(BorderFactory.createEmptyBorder(8, 10, 10, 10));
        text.setPreferredSize(new Dimension(10, 150));
        text.add(name, BorderLayout.NORTH);
        text.add(blurb, BorderLayout.CENTER);
        text.add(price, BorderLayout.SOUTH);
        blurb.setVerticalAlignment(SwingConstants.TOP);

        JPanel card = new JPanel(new BorderLayout());
        card.setBorder(BorderFactory.createLineBorder(new Color(0xB8C4D2), 2, true));
        card.setBackground(Color.WHITE);
        text.setBackground(Color.WHITE);
        card.add(new ProductArt(pr.key(), pr.art()), BorderLayout.CENTER);
        card.add(text, BorderLayout.SOUTH);
        return card;
    }

    /** Re-reads the prices into every product card (called after the Rates tab is saved). */
    void refreshProducts() {
        for (Runnable r : productRefreshers) r.run();
    }

    /** A product that can be bought: picture key, drawing type, name, description, and which price to show. */
    record Product(String key, int art, String name, String blurb, String priceKey, String unit) { }

    // Drawing types used by ProductArt.
    static final int ART_SLAB = 0, ART_MESH = 1, ART_REBAR = 2, ART_FABRIC = 3, ART_POSTS = 4, ART_RAIL = 5,
            ART_WALK = 6, ART_DOUBLE = 7, ART_SLIDING = 8, ART_BARBED = 9, ART_RAZOR = 10, ART_PRIVACY = 11, ART_BAGS = 12;

    /** Every product shown on the Home page. The key is also the file name for an optional photo. */
    static final Product[] PRODUCTS = {
            new Product("concrete", ART_SLAB, "Ready-Mix Concrete",
                    "Poured pads and slabs for sheds, garages, warehouses and parking.", "concrete_per_cy", "per cubic yard"),
            new Product("mesh", ART_MESH, "Wire Mesh Reinforcement",
                    "Welded wire mesh that holds the slab together and controls cracking.", "mesh_per_sqft", "per sq ft"),
            new Product("rebar", ART_REBAR, "Rebar Grid",
                    "Steel rebar tied in a grid for heavy-duty and forklift slabs.", "rebar_per_sqft", "per sq ft"),
            new Product("fabric", ART_FABRIC, "Chain-Link Fabric",
                    "Galvanized woven fence fabric in 4 to 10 ft heights and three gauges. Shown: 6 ft, 11-gauge.",
                    "fence_6ft_11ga", "per linear ft"),
            new Product("posts", ART_POSTS, "Fence Posts",
                    "Galvanized steel, aluminum or Schedule 40 line, corner and gate posts. Shown: galvanized line post.",
                    "post_galvanized_line", "each"),
            new Product("rail", ART_RAIL, "Top Rail",
                    "Strong top rail that keeps the fence straight and tight.", "top_rail_per_lf", "per linear ft"),
            new Product("gate_walk", ART_WALK, "Walk Gate (3-4 ft)",
                    "Single swing gate with hinges and latch for people.", "gate_walk", "each"),
            new Product("gate_double", ART_DOUBLE, "Double Drive Gate (12-16 ft)",
                    "Two-leaf gate wide enough for trucks and equipment.", "gate_double", "each"),
            new Product("gate_sliding", ART_SLIDING, "Sliding Gate (12-40 ft)",
                    "Rolling gate for wide openings, with an optional motor operator.", "gate_sliding_per_ft", "per ft of opening"),
            new Product("barbed", ART_BARBED, "Barbed Wire",
                    "1, 2 or 3 strands along the top of the fence for added security.", "barbed_per_strand_lf", "per ft per strand"),
            new Product("razor", ART_RAZOR, "Razor Wire Coil",
                    "High-security coil for industrial and compound fencing.", "razor_per_lf", "per linear ft"),
            new Product("privacy", ART_PRIVACY, "Privacy Slats",
                    "Slats woven into the fabric to block the view. Priced at 6 ft height.", "privacy_slats_per_lf", "per linear ft"),
            new Product("postconcrete", ART_BAGS, "Post Concrete (60 lb bag)",
                    "Bagged concrete to set every fence post solid in the ground.", "post_concrete_bag", "per bag")};

    /**
     * A picture of one product. If a photo named <key>.png/.jpg/.jpeg/.gif exists in
     * estimator_data/product_images it is shown; otherwise the program draws the product itself.
     */
    // ProductArt: draws the product pictures on the Home tab with Java 2D shapes (no image files needed).
    // It is only decoration; none of it affects the estimate math.
    static final class ProductArt extends JComponent {
        private static final long serialVersionUID = 1L;
        static final int W = 280, H = 160;      // drawing coordinates; scaled to the component size
        static final int GROUND = 128;          // y of the ground line in drawing coordinates
        static final Color STEEL = new Color(0x8C9AA6), DARK_STEEL = new Color(0x56626D);

        final int type;
        final BufferedImage photo;              // optional real photo (null = draw the product)

        ProductArt(String key, int type) {
            this.type = type;
            this.photo = loadPhoto(key);
            setPreferredSize(new Dimension(W, H));
        }

        /** Looks for estimator_data/product_images/<key>.(png|jpg|jpeg|gif). */
        static BufferedImage loadPhoto(String key) {
            Path dir = DATA_DIR.resolve("product_images");
            for (String ext : new String[]{"png", "jpg", "jpeg", "gif"}) {
                Path f = dir.resolve(key + "." + ext);
                if (Files.exists(f)) {
                    try {
                        BufferedImage img = ImageIO.read(f.toFile());
                        if (img != null) return img;
                    } catch (IOException ignored) { /* fall back to the drawing */ }
                }
            }
            return null;
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();

            if (photo != null) {                // real photo: fit it inside the box, keeping its proportions
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, w, h);
                double sc = Math.min((double) w / photo.getWidth(), (double) h / photo.getHeight());
                int pw = (int) (photo.getWidth() * sc), ph = (int) (photo.getHeight() * sc);
                g.drawImage(photo, (w - pw) / 2, (h - ph) / 2, pw, ph, null);
                g.dispose();
                return;
            }

            // Scale the drawing evenly (no stretching) and center it in the box.
            double sc = Math.min(w / (double) W, h / (double) H);
            double ox = (w - W * sc) / 2, oy = (h - H * sc) / 2;
            int gy = (int) Math.round(oy + GROUND * sc);        // ground line in screen coordinates
            // Sky and ground background shared by every drawing, filling the whole box.
            g.setPaint(new GradientPaint(0, 0, new Color(0xCFE3F3), 0, gy, new Color(0xF3F8FC)));
            g.fillRect(0, 0, w, gy);
            g.setPaint(new GradientPaint(0, gy, new Color(0xB7B09F), 0, h, new Color(0x9C9684)));
            g.fillRect(0, gy, w, h - gy);
            g.translate(ox, oy);
            g.scale(sc, sc);

            switch (type) {
                case ART_SLAB -> slab(g);
                case ART_MESH -> mesh(g);
                case ART_REBAR -> rebar(g);
                case ART_FABRIC -> fabric(g);
                case ART_POSTS -> posts(g);
                case ART_RAIL -> rail(g);
                case ART_WALK -> walkGate(g);
                case ART_DOUBLE -> doubleGate(g);
                case ART_SLIDING -> slidingGate(g);
                case ART_BARBED -> barbed(g);
                case ART_RAZOR -> razor(g);
                case ART_PRIVACY -> privacy(g);
                case ART_BAGS -> bags(g);
                default -> { }
            }
            g.dispose();
        }

        /** A closed polygon from x,y pairs. */
        static Path2D poly(double... xy) {
            Path2D.Double p = new Path2D.Double();
            p.moveTo(xy[0], xy[1]);
            for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
            p.closePath();
            return p;
        }

        /** A point inside the four-cornered shape a(front-left) b(front-right) c(back-right) d(back-left). */
        static double[] pt(double[] a, double[] b, double[] c, double[] d, double u, double v) {
            double x = (1 - v) * ((1 - u) * a[0] + u * b[0]) + v * ((1 - u) * d[0] + u * c[0]);
            double y = (1 - v) * ((1 - u) * a[1] + u * b[1]) + v * ((1 - u) * d[1] + u * c[1]);
            return new double[]{x, y};
        }

        /** Straight lines across the four-cornered shape: nu lines one way and nv the other. */
        static void quadGrid(Graphics2D g, double[] a, double[] b, double[] c, double[] d, int nu, int nv) {
            for (int i = 0; i <= nu; i++) {
                double u = (double) i / nu;
                double[] p = pt(a, b, c, d, u, 0), q = pt(a, b, c, d, u, 1);
                g.draw(new Line2D.Double(p[0], p[1], q[0], q[1]));
            }
            for (int j = 0; j <= nv; j++) {
                double v = (double) j / nv;
                double[] p = pt(a, b, c, d, 0, v), q = pt(a, b, c, d, 1, v);
                g.draw(new Line2D.Double(p[0], p[1], q[0], q[1]));
            }
        }

        /** An upright round pipe (post) with side shading. */
        static void post(Graphics2D g, int x, int y, int w, int h, Color base) {
            g.setPaint(new GradientPaint(x, 0, base.brighter(), x + w, 0, base.darker()));
            g.fillRect(x, y, w, h);
            g.setColor(base.darker().darker());
            g.setStroke(new BasicStroke(1f));
            g.drawRect(x, y, w, h);
        }

        /** A flat horizontal pipe (rail) with top-to-bottom shading. */
        static void rod(Graphics2D g, int x, int y, int w, int h, Color base) {
            g.setPaint(new GradientPaint(0, y, base.brighter(), 0, y + h, base.darker()));
            g.fillRect(x, y, w, h);
            g.setColor(base.darker().darker());
            g.setStroke(new BasicStroke(1f));
            g.drawRect(x, y, w, h);
        }

        /** The woven diamond pattern of chain-link fabric, clipped to a rectangle. */
        static void diamonds(Graphics2D g, int x, int y, int w, int h, int step, Color c) {
            Shape old = g.getClip();
            g.clipRect(x, y, w, h);
            g.setColor(c);
            g.setStroke(new BasicStroke(1.2f));
            for (int k = -h; k <= w; k += step) {
                g.drawLine(x + k, y, x + k + h, y + h);
                g.drawLine(x + k + h, y, x + k, y + h);
            }
            g.setClip(old);
        }

        /** Centered small text. */
        static void caption(Graphics2D g, String t, int cx, int y, Color c) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
            g.setColor(c);
            g.drawString(t, cx - g.getFontMetrics().stringWidth(t) / 2, y);
        }

        void slab(Graphics2D g) {                       // a freshly poured concrete slab in perspective
            double[] a = {30, 118}, b = {205, 118}, c = {255, 84}, d = {80, 84};
            g.setPaint(new GradientPaint(30, 118, new Color(0xD6D6D6), 255, 84, new Color(0xBBBBBB)));
            g.fill(poly(a[0], a[1], b[0], b[1], c[0], c[1], d[0], d[1]));
            g.setColor(new Color(0x9C9C9C));
            g.fill(poly(30, 118, 205, 118, 205, 130, 30, 130));         // front edge
            g.setColor(new Color(0x7F7F7F));
            g.fill(poly(205, 118, 255, 84, 255, 96, 205, 130));         // right edge
            g.setColor(new Color(255, 255, 255, 120));                  // trowel lines
            g.setStroke(new BasicStroke(1f));
            for (int i = 1; i < 6; i++) {
                double[] p = pt(a, b, c, d, 0, i / 6.0), q = pt(a, b, c, d, 1, i / 6.0);
                g.draw(new Line2D.Double(p[0], p[1], q[0], q[1]));
            }
            Random r = new Random(7);                                   // sand/aggregate speckles
            g.setColor(new Color(0x8E8E8E));
            for (int i = 0; i < 70; i++) {
                double[] p = pt(a, b, c, d, r.nextDouble(), r.nextDouble());
                g.fill(new Ellipse2D.Double(p[0], p[1], 1.6, 1.6));
            }
            caption(g, "READY-MIX CONCRETE", 140, 150, new Color(0x3C3C3C));
        }

        void mesh(Graphics2D g) {                       // flat sheet of welded wire mesh
            double[] a = {30, 120}, b = {205, 120}, c = {255, 80}, d = {80, 80};
            g.setColor(new Color(255, 255, 255, 150));
            g.fill(poly(a[0], a[1], b[0], b[1], c[0], c[1], d[0], d[1]));
            g.setColor(DARK_STEEL);
            g.setStroke(new BasicStroke(1.3f));
            quadGrid(g, a, b, c, d, 14, 7);
            g.setStroke(new BasicStroke(2.2f));
            g.draw(poly(a[0], a[1], b[0], b[1], c[0], c[1], d[0], d[1]));
            caption(g, "WELDED WIRE MESH", 140, 150, new Color(0x3C3C3C));
        }

        void rebar(Graphics2D g) {                      // two layers of rebar tied in a grid
            double[] a = {30, 120}, b = {205, 120}, c = {255, 80}, d = {80, 80};
            g.setColor(new Color(0xD8D2C3));
            g.fill(poly(a[0], a[1], b[0], b[1], c[0], c[1], d[0], d[1]));
            Color rust = new Color(0x9A4A22);
            g.setColor(rust.darker());
            g.setStroke(new BasicStroke(3.6f));
            for (int i = 0; i <= 5; i++) {                              // bars running left-right (lower layer)
                double[] p = pt(a, b, c, d, 0, i / 5.0), q = pt(a, b, c, d, 1, i / 5.0);
                g.draw(new Line2D.Double(p[0], p[1], q[0], q[1]));
            }
            g.setColor(rust);
            for (int j = 0; j <= 8; j++) {                              // bars running front-back (upper layer)
                double[] p = pt(a, b, c, d, j / 8.0, 0), q = pt(a, b, c, d, j / 8.0, 1);
                g.draw(new Line2D.Double(p[0], p[1], q[0], q[1]));
            }
            g.setColor(new Color(0xF0C419));                            // tie wires at the crossings
            for (int i = 0; i <= 5; i++)
                for (int j = 0; j <= 8; j++) {
                    double[] p = pt(a, b, c, d, j / 8.0, i / 5.0);
                    g.fill(new Ellipse2D.Double(p[0] - 2, p[1] - 2, 4, 4));
                }
            caption(g, "REBAR GRID", 140, 150, new Color(0x3C3C3C));
        }

        void fabric(Graphics2D g) {                     // a section of chain-link fence between two posts
            diamonds(g, 36, 28, 208, 98, 14, STEEL);
            rod(g, 30, 24, 220, 6, new Color(0xA9B4BD));                // top rail
            post(g, 26, 20, 10, 112, new Color(0xA9B4BD));
            post(g, 244, 20, 10, 112, new Color(0xA9B4BD));
            caption(g, "CHAIN-LINK FABRIC", 140, 150, new Color(0x3C3C3C));
        }

        void posts(Graphics2D g) {                      // three post types, each set in a concrete footing
            int[] xs = {55, 135, 215};
            Color[] cols = {new Color(0xA9B4BD), new Color(0xDDE3E8), new Color(0x6E7881)};
            String[] names = {"Galvanized", "Aluminum", "Sched. 40"};
            for (int i = 0; i < 3; i++) {
                g.setColor(new Color(0xB3B3B3));
                g.fill(poly(xs[i] - 14, GROUND, xs[i] + 24, GROUND, xs[i] + 20, GROUND + 22, xs[i] - 10, GROUND + 22)); // footing
                post(g, xs[i], 26, 10, 118, cols[i]);
                g.setColor(cols[i].darker());
                g.fill(new Ellipse2D.Double(xs[i] - 1, 20, 12, 10));    // post cap
                caption(g, names[i], xs[i] + 5, 12, new Color(0x3C3C3C));
            }
        }

        void rail(Graphics2D g) {                       // top rail running through post caps
            post(g, 20, 36, 10, 96, new Color(0xA9B4BD));
            post(g, 250, 36, 10, 96, new Color(0xA9B4BD));
            rod(g, 14, 50, 252, 9, new Color(0xB8C2CA));
            g.setColor(DARK_STEEL);
            g.fillRect(88, 47, 7, 15);                                  // rail sleeves
            g.fillRect(185, 47, 7, 15);
            caption(g, "TOP RAIL", 140, 150, new Color(0x3C3C3C));
        }

        void walkGate(Graphics2D g) {                   // single swing gate
            post(g, 44, 26, 12, 106, new Color(0xA9B4BD));
            post(g, 224, 26, 12, 106, new Color(0xA9B4BD));
            diamonds(g, 60, 44, 160, 80, 12, STEEL);
            g.setColor(DARK_STEEL);
            g.setStroke(new BasicStroke(5f));
            g.drawRect(60, 44, 160, 80);
            g.setColor(new Color(0x2F3A43));                            // hinges and latch
            g.fillRect(52, 52, 8, 12);
            g.fillRect(52, 104, 8, 12);
            g.fill(new Ellipse2D.Double(212, 82, 10, 10));
            caption(g, "WALK GATE", 140, 150, new Color(0x3C3C3C));
        }

        void doubleGate(Graphics2D g) {                 // two-leaf drive gate
            post(g, 12, 24, 12, 108, new Color(0xA9B4BD));
            post(g, 256, 24, 12, 108, new Color(0xA9B4BD));
            diamonds(g, 28, 40, 106, 84, 12, STEEL);
            diamonds(g, 146, 40, 106, 84, 12, STEEL);
            g.setColor(DARK_STEEL);
            g.setStroke(new BasicStroke(5f));
            g.drawRect(28, 40, 106, 84);
            g.drawRect(146, 40, 106, 84);
            g.setColor(new Color(0x2F3A43));
            g.fillRect(24, 50, 6, 12);                                  // hinges
            g.fillRect(24, 104, 6, 12);
            g.fillRect(250, 50, 6, 12);
            g.fillRect(250, 104, 6, 12);
            g.fillRect(136, 78, 8, 14);                                 // center latch
            caption(g, "DOUBLE DRIVE GATE", 140, 150, new Color(0x3C3C3C));
        }

        void slidingGate(Graphics2D g) {                // rolling gate on a track, with motor box
            post(g, 20, 26, 12, 106, new Color(0xA9B4BD));
            post(g, 238, 26, 12, 106, new Color(0xA9B4BD));
            diamonds(g, 66, 40, 160, 80, 12, STEEL);
            g.setColor(DARK_STEEL);
            g.setStroke(new BasicStroke(5f));
            g.drawRect(66, 40, 160, 80);
            g.setStroke(new BasicStroke(3f));
            g.drawLine(14, 126, 266, 126);                              // track
            g.setColor(new Color(0x2F3A43));
            g.fill(new Ellipse2D.Double(80, 120, 12, 12));              // rollers
            g.fill(new Ellipse2D.Double(200, 120, 12, 12));
            g.setColor(new Color(0x2E6F9E));                            // motor operator box
            g.fillRoundRect(252, 92, 22, 30, 5, 5);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            g.drawString("M", 258, 112);
            g.setColor(new Color(0x2E6F9E));                            // arrow showing the slide direction
            g.setStroke(new BasicStroke(2.5f));
            g.drawLine(100, 80, 190, 80);
            g.fill(poly(190, 72, 204, 80, 190, 88));
            caption(g, "SLIDING GATE", 140, 150, new Color(0x3C3C3C));
        }

        void barbed(Graphics2D g) {                     // three strands of barbed wire
            post(g, 12, 20, 10, 112, new Color(0x7A6C5A));
            post(g, 258, 20, 10, 112, new Color(0x7A6C5A));
            int[] ys = {42, 76, 110};
            g.setColor(DARK_STEEL);
            g.setStroke(new BasicStroke(1.6f));
            for (int y : ys) {
                Path2D.Double wire = new Path2D.Double();
                wire.moveTo(22, y);
                for (int x = 22; x <= 258; x += 4) wire.lineTo(x, y + 2.5 * Math.sin(x / 9.0));
                g.draw(wire);
                for (int x = 36; x < 258; x += 26) {                     // barbs
                    g.draw(new Line2D.Double(x - 5, y - 5, x + 5, y + 5));
                    g.draw(new Line2D.Double(x - 5, y + 5, x + 5, y - 5));
                }
            }
            caption(g, "BARBED WIRE", 140, 150, new Color(0x3C3C3C));
        }

        void razor(Graphics2D g) {                      // coil of razor wire
            g.setColor(STEEL);
            g.setStroke(new BasicStroke(2.2f));
            for (int x = 24; x < 236; x += 16) g.draw(new Ellipse2D.Double(x, 38, 40, 62));
            g.setColor(DARK_STEEL);
            g.setStroke(new BasicStroke(1.6f));
            for (int x = 44; x < 256; x += 16) {                         // razor blades on the coil
                g.draw(new Line2D.Double(x, 38, x - 4, 28));
                g.draw(new Line2D.Double(x, 100, x - 4, 110));
            }
            caption(g, "RAZOR WIRE COIL", 140, 150, new Color(0x3C3C3C));
        }

        void privacy(Graphics2D g) {                    // fence with privacy slats woven through
            diamonds(g, 34, 30, 212, 94, 14, STEEL);
            Color[] greens = {new Color(0x3E7D4F), new Color(0x4C9160)};
            for (int x = 38, i = 0; x < 240; x += 9, i++) {
                g.setPaint(new GradientPaint(x, 0, greens[i % 2].brighter(), x + 7, 0, greens[i % 2].darker()));
                g.fillRect(x, 32, 7, 90);
            }
            rod(g, 28, 24, 224, 6, new Color(0xA9B4BD));
            post(g, 24, 20, 10, 112, new Color(0xA9B4BD));
            post(g, 246, 20, 10, 112, new Color(0xA9B4BD));
            caption(g, "PRIVACY SLATS", 140, 150, new Color(0x3C3C3C));
        }

        /** One 60 lb bag of concrete mix with a label. */
        static void bag(Graphics2D g, int x, int y, int w, int h) {
            g.setPaint(new GradientPaint(x, y, new Color(0xD9D3C4), x + w, y + h, new Color(0xB5AE9B)));
            g.fillRoundRect(x, y, w, h, 14, 14);
            g.setColor(new Color(0x8B8472));
            g.setStroke(new BasicStroke(1.5f));
            g.drawRoundRect(x, y, w, h, 14, 14);
            g.setColor(BRAND_BLUE);
            g.fillRect(x + 10, y + h / 4, w - 20, h / 2);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            String t1 = "CONCRETE", t2 = "60 lb";
            g.drawString(t1, x + (w - g.getFontMetrics().stringWidth(t1)) / 2, y + h / 4 + 15);
            g.drawString(t2, x + (w - g.getFontMetrics().stringWidth(t2)) / 2, y + h / 4 + 30);
        }

        void bags(Graphics2D g) {                       // a small stack of concrete bags
            bag(g, 36, 82, 100, 46);
            bag(g, 144, 82, 100, 46);
            bag(g, 90, 38, 100, 46);
            caption(g, "POST CONCRETE", 140, 150, new Color(0x3C3C3C));
        }
    }

    /** The help text, written in simple HTML so it can be shown in a scrollable, formatted panel. */
    static final String HELP_HTML = """
        <html><body style="font-family:sans-serif; font-size:17px; margin:12px;">
        <h1>How This Program Works</h1>
        <p>This program builds a price estimate for a <b>concrete pad</b> and, if you want it, a
        <b>chain-link fence</b> around it. You fill in the tabs from left to right, press
        <b>Calculate Estimate</b>, and a printable report appears.</p>

        <h2>Quick start (5 steps)</h2>
        <ol>
          <li><b>Tab 1 - Project Info:</b> type the project name, client, address, date (YYYY-MM-DD) and your name.</li>
          <li><b>Tab 2 - Concrete Pad:</b> pick a preset size (or choose Custom), pick the slab thickness,
              then enter the crew size and hours.</li>
          <li><b>Tab 3 - Chain-Link Fence:</b> tick <i>Include chain-link fencing</i> only if the job has a fence.
              Pick a preset, add gates and a top treatment if needed.</li>
          <li><b>Tab 5 - Discounts, Contingency and Notes:</b> add any discounts, a contingency percentage and job notes.</li>
          <li>Click <b>Calculate Estimate</b> at the bottom. The report opens on <b>Tab 6</b>.</li>
        </ol>

        <h2>The Home page and header bar</h2>
        <p>The blue bar at the top of the window, <b>Welcome to Myra's Concrete Fence Company</b>, is always visible.
        Click the company name or the <b>Home</b> button at any time to return to the <b>Home</b> tab. The Home tab
        has quick-start buttons and <b>pictures of the products you can buy</b> with their current prices. Prices
        update automatically when you change them on Tab 4. To use your own photos, save them in
        <b>estimator_data/product_images</b> named after the product (for example concrete.jpg, gate_walk.png).</p>

        <h2>What each tab does</h2>
        <ul>
          <li><b>Home</b> - welcome message, quick-start buttons and the product pictures.</li>
          <li><b>1. Project Info</b> - who the estimate is for. All five boxes are required.</li>
          <li><b>2. Concrete Pad</b> - size, thickness, waste (5-15%, default 8%), optional rebar or wire mesh,
              optional equipment rental, crew labor, and discounts for the concrete.</li>
          <li><b>3. Chain-Link Fence</b> - perimeter, height, mesh gauge, post spacing and type, gates, top treatment
              (barbed wire, razor wire, privacy slats), material overage (default 5%), crew labor and fence discounts.</li>
          <li><b>4. Rates and Prices</b> - every material price and labor rate. Double-click a number, change it,
              and press <i>Save changes to CSV</i>.</li>
          <li><b>5. Discounts, Contingency and Notes</b> - grand-total discounts, optional contingency, and a notes box
              that prints on the report.</li>
          <li><b>6. Estimate Output</b> - the finished report.</li>
          <li><b>7. History / Hours Log</b> - every estimate you saved and the crew hours that were logged.</li>
          <li><b>8. Help</b> - this page.</li>
        </ul>

        <h2>The buttons at the bottom</h2>
        <ul>
          <li><b>Calculate Estimate</b> - checks your entries and shows the report.</li>
          <li><b>Save Estimate to CSV</b> - stores the estimate and the work hours in the CSV files (record keeping).</li>
          <li><b>Export Report</b> - saves the report as a .txt file (printable) or a .csv file (spreadsheet).</li>
          <li><b>Print Report</b> - sends the report to your printer.</li>
          <li><b>Help</b> - brings you to this page.</li>
        </ul>

        <h2>Presets</h2>
        <p>Presets are shortcuts. Choose one from the menu and the size fills in automatically. A summary box shows the
        preset details. You can still change any number afterwards - the summary will say
        <i>overridden from preset</i> so you know it was changed. Choose <b>Custom</b> to type your own size.</p>

        <h2>How the numbers are figured</h2>
        <ul>
          <li><b>Area</b> = length x width.</li>
          <li><b>Concrete (cubic yards)</b> = length x width x thickness in feet / 27. Waste is then added on top.</li>
          <li><b>Labor</b> = employees x hours each x hourly rate.</li>
          <li><b>Fence fabric</b> = perimeter minus the gate openings, plus the overage percentage for ordering.</li>
          <li><b>Line posts</b> = fence length / post spacing - 1. Corner, end and gate posts are counted separately.</li>
          <li><b>Hardware</b> (tension bars, caps, ties) = a percentage of the fabric, post and rail cost.</li>
          <li><b>Discounts</b> - the percentage comes off first, then the fixed dollar amount, at three levels:
              concrete, fence and grand total.</li>
          <li><b>Contingency</b> is calculated on the total after all discounts and added at the end.</li>
          <li>Every amount is rounded to two decimal places (cents).</li>
        </ul>

        <h2>Where your information is stored</h2>
        <p>The program makes a folder named <b>estimator_data</b> next to itself with four files:
        <b>material_prices.csv</b>, <b>labor_rates.csv</b>, <b>work_hours_log.csv</b> and <b>estimates.csv</b>.
        You can open them in Excel. Prices you change on Tab 4 are remembered the next time you start the program.</p>

        <h2>Entry rules</h2>
        <ul>
          <li>Numbers only - no letters, and no negative values. A message will tell you which box to fix.</li>
          <li>Crew sizes and gate counts must be whole numbers.</li>
          <li>Concrete waste must be between 5 and 15 percent.</li>
          <li>Gate widths: walk 3-4 ft, double drive 12-16 ft, sliding 12-40 ft.</li>
          <li>A fixed dollar discount cannot be larger than the amount it is taken from.</li>
        </ul>

        <h2>Tips</h2>
        <ul>
          <li>Press <b>Suggest hours</b> next to the labor hours to get a starting number, then change it if you like.</li>
          <li>Update the prices on Tab 4 before you start, so the estimate matches today's costs.</li>
          <li>If the fence is not part of the job, leave the fence checkbox unticked and it will not appear on the report.</li>
        </ul>
        </body></html>
        """;

    /** Builds the Help tab: the HELP_HTML text in a read-only, scrollable panel. */
    JComponent helpTab() {
        JEditorPane pane = new JEditorPane("text/html", HELP_HTML);
        pane.setEditable(false);
        pane.setCaretPosition(0);
        return new JScrollPane(pane);
    }

    /**
     * Shows the welcome window: company name, a friendly greeting, and two buttons.
     * Returns true if the user asked to open the Help tab first.
     */
    static boolean showWelcome() {
        final boolean[] openHelp = {false};
        JDialog d = new JDialog((Frame) null, "Welcome", true); // modal: the main window waits until this closes
        d.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        // Blue banner at the top with the company name.
        JPanel banner = new JPanel(new GridLayout(2, 1, 0, 6));
        banner.setBackground(new Color(0x1F3A5F));
        banner.setBorder(BorderFactory.createEmptyBorder(26, 30, 26, 30));
        JLabel title = new JLabel("Welcome to Myra's Concrete Fence Company", SwingConstants.CENTER);
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 30f));
        JLabel sub = new JLabel("Concrete Pad & Chain-Link Fence Estimator", SwingConstants.CENTER);
        sub.setForeground(new Color(0xD6E4F5));
        sub.setFont(sub.getFont().deriveFont(Font.PLAIN, 20f));
        banner.add(title);
        banner.add(sub);

        // Greeting and an optional "how is your day" reply.
        JLabel hello = new JLabel("Hello, how is your day going.", SwingConstants.CENTER);
        hello.setFont(hello.getFont().deriveFont(Font.BOLD, 26f));
        JLabel reply = new JLabel(" ", SwingConstants.CENTER);
        reply.setFont(reply.getFont().deriveFont(Font.ITALIC, 20f));
        JButton great = new JButton("Great!");
        JButton okay = new JButton("Pretty good");
        JButton busy = new JButton("Busy day");
        great.addActionListener(a -> reply.setText("Wonderful - let's make it even better with a quick estimate!"));
        okay.addActionListener(a -> reply.setText("Glad to hear it. Let's get your estimate done."));
        busy.addActionListener(a -> reply.setText("Understood - this will be quick. Let's get started."));
        JPanel mood = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
        mood.add(great); mood.add(okay); mood.add(busy);

        JPanel middle = new JPanel(new GridLayout(3, 1, 0, 8));
        middle.setBorder(BorderFactory.createEmptyBorder(22, 20, 10, 20));
        middle.add(hello);
        middle.add(mood);
        middle.add(reply);

        // Start / Help buttons.
        JButton start = new JButton("Start Estimating");
        JButton help = new JButton("Read the Help First");
        start.addActionListener(a -> d.dispose());
        help.addActionListener(a -> { openHelp[0] = true; d.dispose(); });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 16, 14));
        buttons.setBorder(BorderFactory.createEmptyBorder(0, 0, 14, 0));
        buttons.add(start);
        buttons.add(help);

        d.setLayout(new BorderLayout());
        d.add(banner, BorderLayout.NORTH);
        d.add(middle, BorderLayout.CENTER);
        d.add(buttons, BorderLayout.SOUTH);
        d.getRootPane().setDefaultButton(start);
        d.setSize(820, 520);
        d.setLocationRelativeTo(null);
        d.setVisible(true); // blocks here until the dialog is closed
        return openHelp[0];
    }

    /** Adds "extra" points to every font in the current look-and-feel and gives buttons roomier padding. */
    static void enlargeFonts(int extra) {
        for (Object key : Collections.list(UIManager.getDefaults().keys())) {
            Object v = UIManager.get(key);
            if (v instanceof Font f)
                UIManager.put(key, new javax.swing.plaf.FontUIResource(f.deriveFont((float) f.getSize() + extra)));
        }
        UIManager.put("Button.margin", new Insets(6, 14, 6, 14));
    }

    // PROGRAM START: main() opens the window, shows the welcome dialog, and (if asked) jumps to the Help tab.
    public static void main(String[] args) {
        // Swing GUIs must be created on the Event Dispatch Thread.
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
            enlargeFonts(3);
            ConcreteFenceEstimator app = new ConcreteFenceEstimator();
            boolean helpFirst = showWelcome();
            app.setVisible(true);
            if (helpFirst) app.tabs.setSelectedComponent(app.helpPane);
        });
    }
}