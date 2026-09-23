import javax.swing.*;
import java.awt.event.ActionListener;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;


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
    }
}

// =============== GUI CLASS ===============

public class FarmStoreManagement extends JFrame {
    private FarmStoreManager manager;
    private JTextArea displayArea;

    public FarmStoreManagement() {
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
        JComboBox<String> categoryBox = new JComboBox<>(
            new String[]{"Pet Food", "Animal Feed", "Equipment", "Supplies", "Other"});
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
        JComboBox<String> speciesBox = new JComboBox<>(
            new String[]{"Chicken", "Duck", "Rabbit", "Hamster", "Goat", "Other"});
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
        JComboBox<String> speciesBox = new JComboBox<>(
            new String[]{"Chicken", "Duck", "Rabbit", "Hamster", "Goat", "Other"});
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

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new FarmStoreManagement());
    }
}
