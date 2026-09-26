import javax.swing.*;
import java.awt.event.ActionListener;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

// ===== NEW IMPORTS (added for pictures + CSV/Excel import-export features) =====
import java.awt.*;                 // Graphics2D, Color, GridLayout, etc. -> used to DRAW the animal picture icons
import java.awt.image.BufferedImage; // BufferedImage -> the in-memory "canvas" each animal picture is drawn onto
import java.io.BufferedReader;     // BufferedReader -> reads CSV files line by line when IMPORTING data
import java.io.BufferedWriter;     // BufferedWriter -> writes CSV files line by line when EXPORTING data
import java.io.File;               // File -> represents the CSV file location on disk
import java.io.FileReader;         // FileReader -> opens a CSV file for reading
import java.io.FileWriter;         // FileWriter -> opens/creates a CSV file for writing
import java.io.IOException;        // IOException -> thrown if a file can't be read/written (e.g., bad path)
import java.util.HashMap;          // HashMap -> caches generated pictures so we only draw each one once
import java.util.Map;              // Map -> interface type used with the HashMap above

/**
 * Farm Store Management System
 * Manages store items, animal sales, and veterinary services
 */

// =============== MODEL CLASSES ===============

class StoreItem {
    private int itemID;
    private String name;
    private String category;
    private double price;
    private int quantity;
    private static int nextID = 1000;

    public StoreItem(String name, String category, double price, int quantity) {
        this.itemID = nextID++;
        this.name = name;
        this.category = category;
        this.price = price;
        this.quantity = quantity;
    }

    public int getItemID() { return itemID; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public double getPrice() { return price; }
    public int getQuantity() { return quantity; }

    public void setPrice(double price) { this.price = price; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public void addStock(int amount) { this.quantity += amount; }
    public void removeStock(int amount) { 
        if (amount <= quantity) this.quantity -= amount; 
    }

    @Override
    public String toString() {
        return String.format("ID:%d | %s (%s) | $%.2f | Stock:%d", 
            itemID, name, category, price, quantity);
    }
}

class Animal {
    private int animalID;
    private String species;
    private String breed;
    private double price;
    private String status; // "Available", "Sold", "Resale"
    private LocalDate dateAdded;
    private String supplier; // Name of breeder if resale
    private static int nextID = 2000;

    public Animal(String species, String breed, double price) {
        this.animalID = nextID++;
        this.species = species;
        this.breed = breed;
        this.price = price;
        this.status = "Available";
        this.dateAdded = LocalDate.now();
        this.supplier = "Farm Bred";
    }

    public Animal(String species, String breed, double price, String supplier) {
        this(species, breed, price);
        this.supplier = supplier;
        this.status = "Resale";
    }

    public int getAnimalID() { return animalID; }
    public String getSpecies() { return species; }
    public String getBreed() { return breed; }
    public double getPrice() { return price; }
    public String getStatus() { return status; }
    public String getSupplier() { return supplier; }

    public void setStatus(String status) { this.status = status; }
    public void setPrice(double price) { this.price = price; }

    @Override
    public String toString() {
        return String.format("ID:%d | %s - %s | $%.2f | %s | %s", 
            animalID, species, breed, price, status, supplier);
    }
}

class Service {
    private int serviceID;
    private String serviceName;
    private String description;
    private double price;
    private static int nextID = 3000;

    public Service(String serviceName, String description, double price) {
        this.serviceID = nextID++;
        this.serviceName = serviceName;
        this.description = description;
        this.price = price;
    }

    public int getServiceID() { return serviceID; }
    public String getServiceName() { return serviceName; }
    public String getDescription() { return description; }
    public double getPrice() { return price; }

    @Override
    public String toString() {
        return String.format("ID:%d | %s | $%.2f", serviceID, serviceName, price);
    }
}

class ServiceBooking {
    private int bookingID;
    private Service service;
    private String customerName;
    private String animalDescription;
    private LocalDateTime scheduledDate;
    private String status; // "Scheduled", "Completed", "Cancelled"
    private double amountPaid;
    private static int nextID = 4000;

    public ServiceBooking(Service service, String customerName, String animalDescription, LocalDateTime scheduledDate) {
        this.bookingID = nextID++;
        this.service = service;
        this.customerName = customerName;
        this.animalDescription = animalDescription;
        this.scheduledDate = scheduledDate;
        this.status = "Scheduled";
        this.amountPaid = 0;
    }

    public int getBookingID() { return bookingID; }
    public Service getService() { return service; }
    public String getCustomerName() { return customerName; }
    public String getAnimalDescription() { return animalDescription; }
    public LocalDateTime getScheduledDate() { return scheduledDate; }
    public String getStatus() { return status; }
    public double getAmountPaid() { return amountPaid; }

    public void setStatus(String status) { this.status = status; }
    public void recordPayment(double amount) { this.amountPaid += amount; }

    @Override
    public String toString() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm");
        return String.format("ID:%d | %s | Customer: %s | Date: %s | Status: %s | Paid: $%.2f", 
            bookingID, service.getServiceName(), customerName, 
            scheduledDate.format(formatter), status, amountPaid);
    }
}

class Transaction {
    private int transactionID;
    private LocalDateTime transactionDate;
    private String type; // "Store Sale", "Animal Sale", "Service"
    private double totalAmount;
    private String details;
    private static int nextID = 5000;

    public Transaction(String type, double totalAmount, String details) {
        this.transactionID = nextID++;
        this.type = type;
        this.totalAmount = totalAmount;
        this.details = details;
        this.transactionDate = LocalDateTime.now();
    }

    public int getTransactionID() { return transactionID; }
    public LocalDateTime getTransactionDate() { return transactionDate; }
    public String getType() { return type; }
    public double getTotalAmount() { return totalAmount; }
    public String getDetails() { return details; }

    @Override
    public String toString() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm");
        return String.format("ID:%d | %s | %s | $%.2f | %s", 
            transactionID, transactionDate.format(formatter), type, totalAmount, details);
    }
}

// =============== NEW: ANIMAL / PRODUCT PICTURE FACTORY ===============
// This class was added so the program can show a small PICTURE next to each
// animal or product (horses, cows, chickens, eggs, milk, etc.).
// Step by step, here is what it does:
//   1. It does NOT download or copy any image from the internet (that would
//      risk using someone else's copyrighted photo).
//   2. Instead it DRAWS a simple, original cartoon-style icon in memory using
//      Java's own Graphics2D drawing tools (circles, ovals, rectangles).
//   3. Each icon is drawn only once and then saved in a "cache" (a HashMap)
//      so the program does not waste time re-drawing the same picture twice.
//   4. The rest of the program asks this class for a picture by name, e.g.
//      AnimalIconFactory.getIcon("Cow"), and gets back a ready-to-display
//      ImageIcon that can be placed on any Swing label, button, or dialog.
class AnimalIconFactory {

    // STEP 1: the cache. Key = animal/product name, Value = the picture already drawn.
    private static final Map<String, ImageIcon> cache = new HashMap<>();

    // STEP 2: the public method the rest of the program calls to get a picture.
    public static ImageIcon getIcon(String name) {
        // 2a. If we already drew this picture before, just hand back the cached copy.
        if (cache.containsKey(name)) {
            return cache.get(name);
        }
        // 2b. Otherwise, draw a brand-new picture for this name.
        BufferedImage image = drawIcon(name);
        ImageIcon icon = new ImageIcon(image);
        // 2c. Save it in the cache for next time.
        cache.put(name, icon);
        return icon;
    }

    // STEP 3: the actual drawing logic. Every icon is drawn on a 64x64 canvas.
    private static BufferedImage drawIcon(String name) {
        int size = 64;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 3a. Pick a background color and body color based on the animal/product name.
        Color background = new Color(235, 245, 235); // light green "pasture" background
        g.setColor(background);
        g.fillRoundRect(0, 0, size, size, 14, 14);

        String key = name == null ? "" : name.trim().toLowerCase();

        // 3b. Each branch below draws a very simple, original shape that represents
        //     the animal or product. These are intentionally simple cartoon shapes,
        //     not copies of any real photo or artwork.
        switch (key) {
            case "horse":
                g.setColor(new Color(120, 72, 42)); // brown body
                g.fillOval(14, 26, 36, 22);          // body
                g.fillRect(38, 12, 10, 22);           // neck/head block
                g.setColor(new Color(60, 40, 25));
                g.fillRect(40, 10, 8, 6);             // mane
                g.setColor(Color.BLACK);
                g.fillOval(44, 16, 4, 4);             // eye
                g.setColor(new Color(120, 72, 42));
                g.fillRect(18, 44, 4, 14);            // legs
                g.fillRect(28, 44, 4, 14);
                g.fillRect(38, 44, 4, 14);
                break;
            case "cow":
                g.setColor(Color.WHITE);
                g.fillOval(12, 24, 40, 24);           // body
                g.setColor(Color.BLACK);
                g.fillOval(18, 28, 8, 8);              // spot
                g.fillOval(32, 34, 10, 8);             // spot
                g.setColor(Color.WHITE);
                g.fillRect(38, 14, 14, 16);            // head
                g.setColor(new Color(255, 200, 200));
                g.fillOval(40, 24, 10, 6);             // pink nose
                g.setColor(Color.BLACK);
                g.fillOval(41, 17, 3, 3);              // eye
                g.fillRect(16, 46, 4, 12);             // legs
                g.fillRect(26, 46, 4, 12);
                g.fillRect(36, 46, 4, 12);
                break;
            case "chicken":
                g.setColor(Color.WHITE);
                g.fillOval(16, 22, 30, 26);            // body
                g.setColor(Color.RED);
                g.fillOval(30, 12, 8, 8);              // comb/head
                g.setColor(new Color(255, 165, 0));
                int[] bx = {40, 48, 40};
                int[] by = {16, 18, 20};
                g.fillPolygon(bx, by, 3);              // beak
                g.setColor(new Color(255, 165, 0));
                g.fillRect(24, 46, 3, 10);              // legs
                g.fillRect(34, 46, 3, 10);
                break;
            case "duck":
                g.setColor(new Color(255, 250, 200));
                g.fillOval(14, 24, 32, 22);
                g.setColor(new Color(80, 140, 90));
                g.fillOval(34, 14, 16, 16);            // head
                g.setColor(new Color(255, 165, 0));
                g.fillOval(46, 20, 10, 6);              // bill
                break;
            case "rabbit":
                g.setColor(Color.LIGHT_GRAY);
                g.fillOval(16, 28, 30, 22);             // body
                g.fillOval(28, 12, 16, 18);             // head
                g.fillRect(28, 2, 4, 14);               // ear
                g.fillRect(36, 2, 4, 14);               // ear
                g.setColor(Color.WHITE);
                g.fillOval(30, 40, 8, 8);               // tail
                break;
            case "goat":
                g.setColor(new Color(230, 220, 200));
                g.fillOval(14, 26, 34, 22);
                g.fillRect(38, 12, 14, 18);
                g.setColor(Color.DARK_GRAY);
                g.fillRect(40, 6, 4, 10);               // horn
                g.fillRect(46, 6, 4, 10);               // horn
                break;
            case "sheep":
                g.setColor(Color.WHITE);
                g.fillOval(12, 22, 36, 26);             // wooly body
                g.setColor(new Color(60, 60, 60));
                g.fillOval(38, 26, 14, 14);             // dark face
                break;
            case "pig":
                g.setColor(new Color(250, 180, 190));
                g.fillOval(14, 24, 36, 24);
                g.setColor(new Color(240, 150, 160));
                g.fillOval(38, 30, 10, 8);              // snout
                break;
            case "hamster":
                g.setColor(new Color(210, 170, 110));
                g.fillOval(18, 26, 28, 22);
                g.fillOval(24, 16, 16, 16);
                break;
            case "eggs":
            case "egg":
                g.setColor(new Color(160, 110, 60));
                g.fillRoundRect(10, 40, 44, 16, 6, 6); // carton base
                g.setColor(new Color(255, 250, 230));
                g.fillOval(14, 20, 14, 20);
                g.fillOval(30, 18, 14, 22);
                g.fillOval(44, 22, 12, 18);
                break;
            case "milk":
                g.setColor(new Color(255, 255, 255));
                g.fillRect(20, 18, 24, 36);
                g.setColor(new Color(180, 210, 240));
                g.fillRect(20, 30, 24, 24);             // milk fill line
                g.setColor(Color.DARK_GRAY);
                g.drawRect(20, 18, 24, 36);
                g.fillPolygon(new int[]{20, 32, 44}, new int[]{18, 8, 18}, 3); // spout
                break;
            default:
                // Fallback generic paw/animal icon for any species not listed above.
                g.setColor(new Color(150, 150, 150));
                g.fillOval(18, 18, 28, 28);
                g.setColor(Color.WHITE);
                g.drawString("?", 28, 36);
                break;
        }

        g.dispose();
        return img;
    }
}

// =============== BUSINESS LOGIC CLASS ===============

class FarmStoreManager {
    private List<StoreItem> storeInventory;
    private List<Animal> animals;
    private List<Service> services;
    private List<ServiceBooking> bookings;
    private List<Transaction> transactions;

    public FarmStoreManager() {
        this.storeInventory = new ArrayList<>();
        this.animals = new ArrayList<>();
        this.services = new ArrayList<>();
        this.bookings = new ArrayList<>();
        this.transactions = new ArrayList<>();
        initializeDefaultData();
    }

    // ===== STORE ITEM MANAGEMENT =====
    public void addStoreItem(String name, String category, double price, int quantity) {
        storeInventory.add(new StoreItem(name, category, price, quantity));
    }

    public boolean sellStoreItem(int itemID, int quantity) {
        for (StoreItem item : storeInventory) {
            if (item.getItemID() == itemID) {
                if (item.getQuantity() >= quantity) {
                    item.removeStock(quantity);
                    transactions.add(new Transaction("Store Sale", 
                        item.getPrice() * quantity, 
                        quantity + "x " + item.getName()));
                    return true;
                } else {
                    return false;
                }
            }
        }
        return false;
    }

    public StoreItem findStoreItem(int itemID) {
        for (StoreItem item : storeInventory) {
            if (item.getItemID() == itemID) return item;
        }
        return null;
    }

    public List<StoreItem> getStoreInventory() {
        return new ArrayList<>(storeInventory);
    }

    public String displayStoreInventory() {
        if (storeInventory.isEmpty()) return "No items in inventory.";
        StringBuilder sb = new StringBuilder("=== STORE INVENTORY ===\n");
        for (StoreItem item : storeInventory) {
            sb.append(item.toString()).append("\n");
        }
        return sb.toString();
    }

    // ===== ANIMAL MANAGEMENT =====
    public void addAnimal(String species, String breed, double price) {
        animals.add(new Animal(species, breed, price));
    }

    public void addResaleAnimal(String species, String breed, double price, String supplier) {
        animals.add(new Animal(species, breed, price, supplier));
    }

    public boolean sellAnimal(int animalID) {
        for (Animal animal : animals) {
            if (animal.getAnimalID() == animalID) {
                if (animal.getStatus().equals("Available") || animal.getStatus().equals("Resale")) {
                    animal.setStatus("Sold");
                    transactions.add(new Transaction("Animal Sale", 
                        animal.getPrice(), 
                        animal.getSpecies() + " - " + animal.getBreed()));
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    public Animal findAnimal(int animalID) {
        for (Animal animal : animals) {
            if (animal.getAnimalID() == animalID) return animal;
        }
        return null;
    }

    public List<Animal> getAvailableAnimals() {
        List<Animal> available = new ArrayList<>();
        for (Animal animal : animals) {
            if (animal.getStatus().equals("Available") || animal.getStatus().equals("Resale")) {
                available.add(animal);
            }
        }
        return available;
    }

    public String displayAnimals() {
        if (animals.isEmpty()) return "No animals available.";
        StringBuilder sb = new StringBuilder("=== ANIMALS FOR SALE ===\n");
        for (Animal animal : animals) {
            sb.append(animal.toString()).append("\n");
        }
        return sb.toString();
    }

    // ===== SERVICE MANAGEMENT =====
    public void addService(String name, String description, double price) {
        services.add(new Service(name, description, price));
    }

    public Service findService(int serviceID) {
        for (Service service : services) {
            if (service.getServiceID() == serviceID) return service;
        }
        return null;
    }

    public List<Service> getServices() {
        return new ArrayList<>(services);
    }

    public String displayServices() {
        if (services.isEmpty()) return "No services available.";
        StringBuilder sb = new StringBuilder("=== AVAILABLE SERVICES ===\n");
        for (Service service : services) {
            sb.append(service.toString()).append("\n");
        }
        return sb.toString();
    }

    // ===== SERVICE BOOKING MANAGEMENT =====
    public void bookService(int serviceID, String customerName, String animalDescription, LocalDateTime scheduledDate) {
        Service service = findService(serviceID);
        if (service != null) {
            bookings.add(new ServiceBooking(service, customerName, animalDescription, scheduledDate));
        }
    }

    public boolean completeService(int bookingID, double paymentAmount) {
        for (ServiceBooking booking : bookings) {
            if (booking.getBookingID() == bookingID) {
                booking.setStatus("Completed");
                booking.recordPayment(paymentAmount);
                transactions.add(new Transaction("Service", 
                    paymentAmount, 
                    booking.getService().getServiceName() + " - " + booking.getCustomerName()));
                return true;
            }
        }
        return false;
    }

    public List<ServiceBooking> getUpcomingBookings() {
        List<ServiceBooking> upcoming = new ArrayList<>();
        for (ServiceBooking booking : bookings) {
            if (booking.getStatus().equals("Scheduled")) {
                upcoming.add(booking);
            }
        }
        return upcoming;
    }

    public String displayBookings() {
        if (bookings.isEmpty()) return "No service bookings.";
        StringBuilder sb = new StringBuilder("=== SERVICE BOOKINGS ===\n");
        for (ServiceBooking booking : bookings) {
            sb.append(booking.toString()).append("\n");
        }
        return sb.toString();
    }

    // ===== FINANCIAL REPORTING =====
    public double getTotalRevenue() {
        double total = 0;
        for (Transaction trans : transactions) {
            total += trans.getTotalAmount();
        }
        return total;
    }

    public double getRevenueByType(String type) {
        double total = 0;
        for (Transaction trans : transactions) {
            if (trans.getType().equals(type)) {
                total += trans.getTotalAmount();
            }
        }
        return total;
    }

    public String generateFinancialReport() {
        StringBuilder sb = new StringBuilder("=== FINANCIAL REPORT ===\n");
        sb.append(String.format("Total Revenue: $%.2f\n", getTotalRevenue()));
        sb.append(String.format("Store Sales: $%.2f\n", getRevenueByType("Store Sale")));
        sb.append(String.format("Animal Sales: $%.2f\n", getRevenueByType("Animal Sale")));
        sb.append(String.format("Services: $%.2f\n", getRevenueByType("Service")));
        sb.append("\n=== TRANSACTIONS ===\n");
        for (Transaction trans : transactions) {
            sb.append(trans.toString()).append("\n");
        }
        return sb.toString();
    }

    private void initializeDefaultData() {
        // Store items
        addStoreItem("Dog Food (Bag)", "Pet Food", 25.99, 15);
        addStoreItem("Cat Litter", "Pet Supplies", 12.99, 20);
        addStoreItem("Chicken Feed", "Animal Feed", 15.99, 30);
        addStoreItem("Hay Bales", "Animal Feed", 8.99, 50);
        addStoreItem("Water Trough", "Equipment", 45.99, 8);

        // Animals for sale
        addAnimal("Chicken", "Rhode Island Red", 8.99);
        addAnimal("Duck", "Pekin", 12.99);
        addAnimal("Hamster", "Syrian", 15.99);
        addAnimal("Rabbit", "Holland Lop", 35.99);
        addResaleAnimal("Goat", "Nigerian Dwarf", 150.00, "Miller's Farm Breeder");

        // Services
        addService("Basic Vet Check", "Health examination and basic treatment", 50.00);
        addService("Animal Vaccination", "Vaccinations for farm animals", 35.00);
        addService("Grooming Service", "Basic grooming for small animals", 25.00);
        addService("Training Consultation", "Advice on animal care and training", 40.00);

        // ===== NEW DEFAULT DATA (added on top of the original list above) =====
        // New farm animals: horses and cows, as requested.
        addAnimal("Horse", "Quarter Horse", 1200.00);
        addAnimal("Horse", "Shetland Pony", 650.00);
        addResaleAnimal("Cow", "Holstein Dairy Cow", 900.00, "Green Pastures Ranch");
        addAnimal("Cow", "Jersey Calf", 450.00);
        addAnimal("Sheep", "Suffolk", 175.00);
        addAnimal("Pig", "Yorkshire Piglet", 95.00);

        // New farm produce/products: eggs and milk, sold like any store item.
        addStoreItem("Fresh Eggs (Dozen)", "Dairy & Eggs", 4.50, 40);
        addStoreItem("Fresh Milk (Gallon)", "Dairy & Eggs", 5.25, 25);
        addStoreItem("Farm Cheese (Block)", "Dairy & Eggs", 7.00, 12);
        addStoreItem("Butter (Pack)", "Dairy & Eggs", 4.00, 18);

        // New farm services covering the new animals and general farm work.
        addService("Horse Shoeing / Farrier Visit", "Hoof trimming and shoe fitting for horses", 85.00);
        addService("Cattle Health Check", "Full wellness check for cows and calves", 70.00);
        addService("Milking Assistance", "Hands-on help with dairy milking routine", 30.00);
        addService("Shearing Service", "Wool shearing for sheep", 45.00);
        addService("Farm Tour / Petting Visit", "Guided walk-through of the farm and animals", 15.00);
        addService("Pasture & Fencing Consultation", "On-site advice for grazing and fencing setup", 60.00);
        addService("Hoof & Nail Trim (Small Animals)", "Trimming for rabbits, goats, and hamsters", 20.00);
    }

    // ================================================================
    // ===== NEW SECTION: CSV IMPORT / EXPORT (also Excel-friendly) =====
    // ================================================================
    // Why CSV: a .csv file is plain text with commas separating each value.
    // Microsoft Excel opens .csv files natively (double-click, or File > Open),
    // so these export methods give you both a "CSV file" and an "Excel-ready
    // file" at the same time, without needing any extra software library.
    // A true binary .xlsx file needs a third-party library (Apache POI) that
    // is not part of standard Java, so CSV is used here to keep the program
    // simple to compile and run for the class project.

    // STEP 1: a small helper that safely wraps a text value in quotes for CSV,
    // so names/descriptions containing commas still work correctly.
    private String csv(Object value) {
        String s = (value == null) ? "" : value.toString();
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    // STEP 2: export the store inventory to a CSV file.
    public void exportStoreInventoryToCSV(String filePath) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            // 2a. Write the header row first (column titles).
            writer.write("ItemID,Name,Category,Price,Quantity");
            writer.newLine();
            // 2b. Write one row per store item.
            for (StoreItem item : storeInventory) {
                writer.write(item.getItemID() + "," + csv(item.getName()) + "," +
                    csv(item.getCategory()) + "," + item.getPrice() + "," + item.getQuantity());
                writer.newLine();
            }
        }
    }

    // STEP 3: export the animals list (now including horses and cows) to CSV.
    public void exportAnimalsToCSV(String filePath) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            writer.write("AnimalID,Species,Breed,Price,Status,Supplier");
            writer.newLine();
            for (Animal animal : animals) {
                writer.write(animal.getAnimalID() + "," + csv(animal.getSpecies()) + "," +
                    csv(animal.getBreed()) + "," + animal.getPrice() + "," +
                    csv(animal.getStatus()) + "," + csv(animal.getSupplier()));
                writer.newLine();
            }
        }
    }

    // STEP 4: export the services list to CSV.
    public void exportServicesToCSV(String filePath) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            writer.write("ServiceID,ServiceName,Description,Price");
            writer.newLine();
            for (Service service : services) {
                writer.write(service.getServiceID() + "," + csv(service.getServiceName()) + "," +
                    csv(service.getDescription()) + "," + service.getPrice());
                writer.newLine();
            }
        }
    }

    // STEP 5: export the service bookings to CSV.
    public void exportBookingsToCSV(String filePath) throws IOException {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            writer.write("BookingID,Service,Customer,AnimalDescription,ScheduledDate,Status,AmountPaid");
            writer.newLine();
            for (ServiceBooking booking : bookings) {
                writer.write(booking.getBookingID() + "," + csv(booking.getService().getServiceName()) + "," +
                    csv(booking.getCustomerName()) + "," + csv(booking.getAnimalDescription()) + "," +
                    csv(booking.getScheduledDate().format(formatter)) + "," +
                    csv(booking.getStatus()) + "," + booking.getAmountPaid());
                writer.newLine();
            }
        }
    }

    // STEP 6: export the full transaction history to CSV (good for Excel bookkeeping).
    public void exportTransactionsToCSV(String filePath) throws IOException {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm");
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            writer.write("TransactionID,Date,Type,TotalAmount,Details");
            writer.newLine();
            for (Transaction trans : transactions) {
                writer.write(trans.getTransactionID() + "," + csv(trans.getTransactionDate().format(formatter)) + "," +
                    csv(trans.getType()) + "," + trans.getTotalAmount() + "," + csv(trans.getDetails()));
                writer.newLine();
            }
        }
    }

    // STEP 7: import additional store items from a CSV file the user prepared
    // (for example, in Excel). Expected columns: Name,Category,Price,Quantity
    // The first line is treated as a header and skipped.
    public int importStoreItemsFromCSV(String filePath) throws IOException {
        int importedCount = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine) { firstLine = false; continue; } // skip header row
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split(",");
                if (parts.length >= 4) {
                    try {
                        String name = parts[0].replace("\"", "").trim();
                        String category = parts[1].replace("\"", "").trim();
                        double price = Double.parseDouble(parts[2].replace("\"", "").trim());
                        int quantity = Integer.parseInt(parts[3].replace("\"", "").trim());
                        addStoreItem(name, category, price, quantity);
                        importedCount++;
                    } catch (NumberFormatException ignored) {
                        // Skip any row that doesn't have valid numbers.
                    }
                }
            }
        }
        return importedCount;
    }
}

// =============== GUI CLASS ===============

public class FarmStoreManagement extends JFrame {
    private FarmStoreManager manager;
    private JTextArea displayArea;

    public FarmStoreManagement() {
        // For unknown reasons, the println() here seems to keep the crash from happening
        // Possibly it gives time for the class to load?
        System.err.println("Starting FarmStoreManagement() constructor...");
        setTitle("Farm Store Management System");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 700);
        setLocationRelativeTo(null);
        setResizable(true);

        manager = new FarmStoreManager();

        // Create menu bar
        JMenuBar menuBar = createMenuBar();
        setJMenuBar(menuBar);

        // Create main display area
        displayArea = new JTextArea();
        displayArea.setEditable(false);
        displayArea.setFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 11));
        displayArea.setLineWrap(true);
        displayArea.setWrapStyleWord(true);

        JScrollPane scrollPane = new JScrollPane(displayArea);
        add(scrollPane, java.awt.BorderLayout.CENTER);

        // Show welcome screen
        showWelcomeScreen();

        setVisible(true);
    }

    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // Store Menu
        JMenu storeMenu = new JMenu("Store");
        storeMenu.add(createMenuItem("View Inventory", event -> displayStoreInventory()));
        storeMenu.add(createMenuItem("Sell Item", event -> sellStoreItem()));
        storeMenu.add(createMenuItem("Add Item", event -> addStoreItem()));
        menuBar.add(storeMenu);

        // Animals Menu
        JMenu animalMenu = new JMenu("Animals");
        animalMenu.add(createMenuItem("View Animals", event -> displayAnimals()));
        animalMenu.add(createMenuItem("Sell Animal", event -> sellAnimal()));
        animalMenu.add(createMenuItem("Add Animal", event -> addAnimal()));
        animalMenu.add(createMenuItem("Add Resale Animal", event -> addResaleAnimal()));
        menuBar.add(animalMenu);

        // Services Menu
        JMenu serviceMenu = new JMenu("Services");
        serviceMenu.add(createMenuItem("View Services", event -> displayServices()));
        serviceMenu.add(createMenuItem("Book Service", event -> bookService()));
        serviceMenu.add(createMenuItem("Complete Service", event -> completeService()));
        serviceMenu.add(createMenuItem("View Bookings", event -> displayBookings()));
        menuBar.add(serviceMenu);

        // Reports Menu
        JMenu reportMenu = new JMenu("Reports");
        reportMenu.add(createMenuItem("Financial Report", event -> displayFinancialReport()));
        menuBar.add(reportMenu);

        // ===== NEW MENU: Data (CSV / Excel) =====
        // Lets the user export any list to a .csv file (openable directly in Excel)
        // and import extra store items from a .csv file someone prepared in Excel.
        JMenu dataMenu = new JMenu("Data (CSV/Excel)");
        dataMenu.add(createMenuItem("Export Inventory to CSV", event -> exportInventoryCSV()));
        dataMenu.add(createMenuItem("Export Animals to CSV", event -> exportAnimalsCSV()));
        dataMenu.add(createMenuItem("Export Services to CSV", event -> exportServicesCSV()));
        dataMenu.add(createMenuItem("Export Bookings to CSV", event -> exportBookingsCSV()));
        dataMenu.add(createMenuItem("Export Transactions to CSV", event -> exportTransactionsCSV()));
        dataMenu.add(createMenuItem("Export ALL Data to CSV", event -> exportAllCSV()));
        dataMenu.addSeparator();
        dataMenu.add(createMenuItem("Import Store Items from CSV", event -> importStoreItemsCSV()));
        menuBar.add(dataMenu);

        // ===== NEW MENU: Gallery =====
        // Shows a window with a small picture for each animal/product.
        JMenu galleryMenu = new JMenu("Gallery");
        galleryMenu.add(createMenuItem("View Animal & Product Pictures", event -> showAnimalGallery()));
        menuBar.add(galleryMenu);

        // Help Menu
        JMenu helpMenu = new JMenu("Help");
        helpMenu.add(createMenuItem("About", event -> showAbout()));
        helpMenu.add(createMenuItem("Welcome Screen", event -> showWelcomeScreen()));
        menuBar.add(helpMenu);

        return menuBar;
    }

    private JMenuItem createMenuItem(String label, ActionListener listener) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(listener);
        return item;
    }

    private void showWelcomeScreen() {
        String welcome = "╔════════════════════════════════════════════════════╗\n" +
            "║        WELCOME TO FARM STORE MANAGEMENT            ║\n" +
            "║              Your Family Farm Business             ║\n" +
            "╚════════════════════════════════════════════════════╝\n\n" +
            "This system helps you manage:\n\n" +
            "STORE INVENTORY\n" +
            "   * Track farm-related products\n" +
            "   * Monitor stock levels\n" +
            "   * Record sales transactions\n\n" +
            "ANIMAL SALES\n" +
            "   * Manage farm-bred animals (chickens, ducks, rabbits, etc.)\n" +
            "   * Track specialty resale animals from local breeders\n" +
            "   * Record animal sales\n\n" +
            "VETERINARY & SERVICES\n" +
            "   * Schedule service appointments\n" +
            "   * Track payment received\n" +
            "   * Manage bookings\n\n" +
            "FINANCIAL TRACKING\n" +
            "   * View revenue by category\n" +
            "   * Generate financial reports\n" +
            "   * Track all transactions\n\n" +
            "════════════════════════════════════════════════════\n" +
            "Use the menu bar to navigate through different features.\n";
        displayArea.setText(welcome);
    }

    private void showAbout() {
        String about = "╔════════════════════════════════════════════════════╗\n" +
            "║                   ABOUT THIS SYSTEM                ║\n" +
            "╚════════════════════════════════════════════════════╝\n\n" +
            "Farm Store Management System\n" +
            "Version 1.0\n\n" +
            "Purpose:\n" +
            "This application digitizes your aunt and uncle's paper-based\n" +
            "farm business operations. It provides a modern, user-friendly\n" +
            "interface to manage all aspects of your farm business.\n\n" +
            "Features:\n" +
            "* Complete inventory management\n" +
            "* Animal sales tracking\n" +
            "* Service scheduling and payment tracking\n" +
            "* Financial reporting and analysis\n" +
            "* Transaction history\n\n" +
            "Architecture:\n" +
            "* Object-Oriented Design with dedicated classes\n" +
            "* Separation of concerns (Business Logic & GUI)\n" +
            "* ArrayList-based data management\n" +
            "* User-friendly dialogs and menus\n\n" +
            "For Support:\n" +
            "Contact your development team or add more features as needed.\n";
        displayArea.setText(about);
    }

    private void displayStoreInventory() {
        displayArea.setText(manager.displayStoreInventory());
    }

    private void displayAnimals() {
        displayArea.setText(manager.displayAnimals());
    }

    private void displayServices() {
        displayArea.setText(manager.displayServices());
    }

    private void displayBookings() {
        displayArea.setText(manager.displayBookings());
    }

    private void displayFinancialReport() {
        displayArea.setText(manager.generateFinancialReport());
    }

    private void addStoreItem() {
        JTextField nameField = new JTextField();
        // NOTE: "Dairy & Eggs" category added so eggs, milk, cheese, and butter fit properly.
        JComboBox<String> categoryBox = new JComboBox<>(
            new String[]{"Pet Food", "Animal Feed", "Equipment", "Supplies", "Dairy & Eggs", "Other"});
        JTextField priceField = new JTextField();
        JTextField quantityField = new JTextField();

        Object[] fields = {
            "Item Name:", nameField,
            "Category:", categoryBox,
            "Price:", priceField,
            "Quantity:", quantityField
        };

        if (JOptionPane.showConfirmDialog(this, fields, "Add Store Item", 
            JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            try {
                String name = nameField.getText();
                String category = (String) categoryBox.getSelectedItem();
                double price = Double.parseDouble(priceField.getText());
                int quantity = Integer.parseInt(quantityField.getText());

                if (name.isEmpty() || price <= 0 || quantity <= 0) {
                    JOptionPane.showMessageDialog(this, "Please enter valid information.", 
                        "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }

                manager.addStoreItem(name, category, price, quantity);
                JOptionPane.showMessageDialog(this, "Item added successfully!", 
                    "Success", JOptionPane.INFORMATION_MESSAGE);
                displayStoreInventory();
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Invalid price or quantity.", 
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void sellStoreItem() {
        JTextField itemIDField = new JTextField();
        JTextField quantityField = new JTextField();

        Object[] fields = {
            "Item ID:", itemIDField,
            "Quantity:", quantityField
        };

        if (JOptionPane.showConfirmDialog(this, fields, "Sell Store Item", 
            JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            try {
                int itemID = Integer.parseInt(itemIDField.getText());
                int quantity = Integer.parseInt(quantityField.getText());

                if (manager.sellStoreItem(itemID, quantity)) {
                    JOptionPane.showMessageDialog(this, "Sale completed successfully!", 
                        "Success", JOptionPane.INFORMATION_MESSAGE);
                    displayStoreInventory();
                } else {
                    JOptionPane.showMessageDialog(this, "Insufficient stock or item not found.", 
                        "Error", JOptionPane.ERROR_MESSAGE);
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Invalid input.", 
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void addAnimal() {
        // NOTE: dropdown list expanded to include Horse, Cow, Sheep, and Pig as requested.
        JComboBox<String> speciesBox = new JComboBox<>(
            new String[]{"Chicken", "Duck", "Rabbit", "Hamster", "Goat", "Horse", "Cow", "Sheep", "Pig", "Other"});
        JTextField breedField = new JTextField();
        JTextField priceField = new JTextField();

        Object[] fields = {
            "Species:", speciesBox,
            "Breed:", breedField,
            "Price:", priceField
        };

        if (JOptionPane.showConfirmDialog(this, fields, "Add Farm Animal", 
            JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            try {
                String species = (String) speciesBox.getSelectedItem();
                String breed = breedField.getText();
                double price = Double.parseDouble(priceField.getText());

                if (breed.isEmpty() || price <= 0) {
                    JOptionPane.showMessageDialog(this, "Please enter valid information.", 
                        "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }

                manager.addAnimal(species, breed, price);
                JOptionPane.showMessageDialog(this, "Animal added successfully!", 
                    "Success", JOptionPane.INFORMATION_MESSAGE);
                displayAnimals();
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Invalid price.", 
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void addResaleAnimal() {
        // NOTE: dropdown list expanded to include Horse, Cow, Sheep, and Pig as requested.
        JComboBox<String> speciesBox = new JComboBox<>(
            new String[]{"Chicken", "Duck", "Rabbit", "Hamster", "Goat", "Horse", "Cow", "Sheep", "Pig", "Other"});
        JTextField breedField = new JTextField();
        JTextField priceField = new JTextField();
        JTextField supplierField = new JTextField();

        Object[] fields = {
            "Species:", speciesBox,
            "Breed:", breedField,
            "Price:", priceField,
            "Breeder Name:", supplierField
        };

        if (JOptionPane.showConfirmDialog(this, fields, "Add Resale Animal", 
            JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            try {
                String species = (String) speciesBox.getSelectedItem();
                String breed = breedField.getText();
                double price = Double.parseDouble(priceField.getText());
                String supplier = supplierField.getText();

                if (breed.isEmpty() || supplier.isEmpty() || price <= 0) {
                    JOptionPane.showMessageDialog(this, "Please enter valid information.", 
                        "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }

                manager.addResaleAnimal(species, breed, price, supplier);
                JOptionPane.showMessageDialog(this, "Resale animal added successfully!", 
                    "Success", JOptionPane.INFORMATION_MESSAGE);
                displayAnimals();
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Invalid price.", 
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void sellAnimal() {
        JTextField animalIDField = new JTextField();

        Object[] fields = {
            "Animal ID:", animalIDField
        };

        if (JOptionPane.showConfirmDialog(this, fields, "Sell Animal", 
            JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            try {
                int animalID = Integer.parseInt(animalIDField.getText());

                if (manager.sellAnimal(animalID)) {
                    JOptionPane.showMessageDialog(this, "Animal sold successfully!", 
                        "Success", JOptionPane.INFORMATION_MESSAGE);
                    displayAnimals();
                } else {
                    JOptionPane.showMessageDialog(this, "Animal not found or already sold.", 
                        "Error", JOptionPane.ERROR_MESSAGE);
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Invalid animal ID.", 
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void bookService() {
        List<Service> services = manager.getServices();
        if (services.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No services available.", 
                "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        JComboBox<Service> serviceBox = new JComboBox<>(services.toArray(new Service[0]));
        JTextField customerField = new JTextField();
        JTextField animalField = new JTextField();
        JTextField dateField = new JTextField("2024-12-20 10:00");

        Object[] fields = {
            "Service:", serviceBox,
            "Customer Name:", customerField,
            "Animal Description:", animalField,
            "Date & Time (YYYY-MM-DD HH:mm):", dateField
        };

        if (JOptionPane.showConfirmDialog(this, fields, "Book Service", 
            JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            try {
                Service service = (Service) serviceBox.getSelectedItem();
                String customer = customerField.getText();
                String animal = animalField.getText();
                LocalDateTime dateTime = LocalDateTime.parse(dateField.getText(), 
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

                if (customer.isEmpty() || animal.isEmpty()) {
                    JOptionPane.showMessageDialog(this, "Please enter all information.", 
                        "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }

                manager.bookService(service.getServiceID(), customer, animal, dateTime);
                JOptionPane.showMessageDialog(this, "Service booked successfully!", 
                    "Success", JOptionPane.INFORMATION_MESSAGE);
                displayBookings();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Invalid date format. Use YYYY-MM-DD HH:mm", 
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void completeService() {
        JTextField bookingIDField = new JTextField();
        JTextField paymentField = new JTextField();

        Object[] fields = {
            "Booking ID:", bookingIDField,
            "Payment Amount:", paymentField
        };

        if (JOptionPane.showConfirmDialog(this, fields, "Complete Service", 
            JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            try {
                int bookingID = Integer.parseInt(bookingIDField.getText());
                double payment = Double.parseDouble(paymentField.getText());

                if (payment <= 0) {
                    JOptionPane.showMessageDialog(this, "Payment must be greater than 0.", 
                        "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }

                if (manager.completeService(bookingID, payment)) {
                    JOptionPane.showMessageDialog(this, "Service completed and payment recorded!", 
                        "Success", JOptionPane.INFORMATION_MESSAGE);
                    displayBookings();
                } else {
                    JOptionPane.showMessageDialog(this, "Booking not found.", 
                        "Error", JOptionPane.ERROR_MESSAGE);
                }
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Invalid input.", 
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // ================================================================
    // ===== NEW METHODS: CSV / Excel export & import button actions =====
    // ================================================================
    // Each method below follows the same simple pattern:
    //   1. Ask the manager (business logic) to write/read a CSV file.
    //   2. Show a popup telling the user it worked (or what went wrong).
    // Files are saved with a JFileChooser so the user picks where they go,
    // and they are given a .csv extension so Excel recognizes them.

    private void exportInventoryCSV() {
        exportHelper("store_inventory.csv", path -> manager.exportStoreInventoryToCSV(path));
    }

    private void exportAnimalsCSV() {
        exportHelper("animals.csv", path -> manager.exportAnimalsToCSV(path));
    }

    private void exportServicesCSV() {
        exportHelper("services.csv", path -> manager.exportServicesToCSV(path));
    }

    private void exportBookingsCSV() {
        exportHelper("bookings.csv", path -> manager.exportBookingsToCSV(path));
    }

    private void exportTransactionsCSV() {
        exportHelper("transactions.csv", path -> manager.exportTransactionsToCSV(path));
    }

    // Exports all five CSV files at once into a folder the user picks.
    private void exportAllCSV() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose a folder to save ALL CSV files into");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            String folder = chooser.getSelectedFile().getAbsolutePath();
            try {
                manager.exportStoreInventoryToCSV(folder + File.separator + "store_inventory.csv");
                manager.exportAnimalsToCSV(folder + File.separator + "animals.csv");
                manager.exportServicesToCSV(folder + File.separator + "services.csv");
                manager.exportBookingsToCSV(folder + File.separator + "bookings.csv");
                manager.exportTransactionsToCSV(folder + File.separator + "transactions.csv");
                JOptionPane.showMessageDialog(this, "All 5 CSV files were saved to:\n" + folder,
                    "Export Complete", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Could not save files: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // A small functional interface so the five export methods above can share one helper.
    private interface CsvWriteAction {
        void run(String path) throws IOException;
    }

    private void exportHelper(String defaultFileName, CsvWriteAction action) {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(defaultFileName));
        chooser.setDialogTitle("Save CSV File");
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            String path = chooser.getSelectedFile().getAbsolutePath();
            if (!path.toLowerCase().endsWith(".csv")) path += ".csv";
            try {
                action.run(path);
                JOptionPane.showMessageDialog(this, "Saved successfully to:\n" + path +
                    "\n\nThis file can be opened directly in Microsoft Excel.",
                    "Export Complete", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Could not save file: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void importStoreItemsCSV() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose a CSV file to import (columns: Name,Category,Price,Quantity)");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            String path = chooser.getSelectedFile().getAbsolutePath();
            try {
                int count = manager.importStoreItemsFromCSV(path);
                JOptionPane.showMessageDialog(this, count + " store item(s) imported successfully!",
                    "Import Complete", JOptionPane.INFORMATION_MESSAGE);
                displayStoreInventory();
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Could not read file: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    // ================================================================
    // ===== NEW METHOD: Picture Gallery window =====
    // ================================================================
    // Opens a separate window (JDialog) that shows a small drawn picture
    // and label for every animal species and farm product in the system.
    private void showAnimalGallery() {
        String[] names = {
            "Chicken", "Duck", "Rabbit", "Hamster", "Goat",
            "Horse", "Cow", "Sheep", "Pig", "Eggs", "Milk"
        };

        JDialog gallery = new JDialog(this, "Animal & Product Picture Gallery", true);
        gallery.setLayout(new GridLayout(0, 4, 12, 12)); // 4 pictures per row, auto rows

        for (String name : names) {
            JPanel cell = new JPanel(new BorderLayout());
            JLabel pictureLabel = new JLabel(AnimalIconFactory.getIcon(name));
            JLabel nameLabel = new JLabel(name, SwingConstants.CENTER);
            cell.add(pictureLabel, BorderLayout.CENTER);
            cell.add(nameLabel, BorderLayout.SOUTH);
            cell.setBorder(BorderFactory.createEtchedBorder());
            gallery.add(cell);
        }

        gallery.setSize(500, 350);
        gallery.setLocationRelativeTo(this);
        gallery.setVisible(true);
    }

    public static void main(String[] args) {
        // not sure about invokeLater(), trying something simpler
        System.out.println("Starting FarmStoreManagement...");
        SwingUtilities.invokeLater(() -> new FarmStoreManagement());
        
        //FarmStoreManagement app = new FarmStoreManagement();
        //app.setVisible(true);
    }
}
