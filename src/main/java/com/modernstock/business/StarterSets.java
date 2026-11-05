package com.modernstock.business;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Starter data for the setup wizard (FR-6.23): units and categories that fit each kind of shop, and a few sample
 * products to try the POS with. Units are matched by symbol; a sample product names its category by position.
 */
public final class StarterSets {

    public record UnitSeed(String symbol, String nameEn, String nameKm) {
    }

    public record CategorySeed(String nameEn, String nameKm) {
    }

    /** A product sold by {@code unit}; with a pack, also by the pack (e.g. a carton of 24) at its own price. */
    public record ProductSeed(String nameEn, String nameKm, int category, String unit, BigDecimal price, BigDecimal reorderLevel,
                              boolean trackExpiry, String packUnit, BigDecimal packFactor, BigDecimal packPrice) {
    }

    public record StarterSet(String type, List<UnitSeed> units, List<CategorySeed> categories, List<ProductSeed> products) {
    }

    private static final UnitSeed PCS = new UnitSeed("pcs", "Piece", "ដុំ");
    private static final UnitSeed BOX = new UnitSeed("box", "Box", "ប្រអប់");
    private static final UnitSeed PACK = new UnitSeed("pack", "Pack", "កញ្ចប់");
    private static final UnitSeed BTL = new UnitSeed("btl", "Bottle", "ដប");
    private static final UnitSeed CAN = new UnitSeed("can", "Can", "កំប៉ុង");
    private static final UnitSeed KG = new UnitSeed("kg", "Kilogram", "គីឡូក្រាម");
    private static final UnitSeed L = new UnitSeed("L", "Litre", "លីត្រ");
    private static final UnitSeed CASE = new UnitSeed("case", "Case", "កេស");
    private static final UnitSeed BAG = new UnitSeed("bag", "Bag", "បាវ");
    private static final UnitSeed TRAY = new UnitSeed("tray", "Tray", "ថាស");
    private static final UnitSeed TAB = new UnitSeed("tab", "Tablet", "គ្រាប់");
    private static final UnitSeed STRIP = new UnitSeed("strip", "Strip", "បន្ទះ");
    private static final UnitSeed TUBE = new UnitSeed("tube", "Tube", "បំពង់");
    private static final UnitSeed SET = new UnitSeed("set", "Set", "ឈុត");
    private static final UnitSeed M = new UnitSeed("m", "Metre", "ម៉ែត្រ");
    private static final UnitSeed ROLL = new UnitSeed("roll", "Roll", "មូរ");

    private static ProductSeed p(String nameEn, String nameKm, int category, String unit, String price, String reorder, boolean expiry) {
        return new ProductSeed(nameEn, nameKm, category, unit, new BigDecimal(price), new BigDecimal(reorder), expiry, null, null, null);
    }

    private static ProductSeed p(String nameEn, String nameKm, int category, String unit, String price, String reorder, boolean expiry,
                                 String packUnit, int packFactor, String packPrice) {
        return new ProductSeed(nameEn, nameKm, category, unit, new BigDecimal(price), new BigDecimal(reorder), expiry, packUnit,
                BigDecimal.valueOf(packFactor), new BigDecimal(packPrice));
    }

    private static final Map<String, StarterSet> SETS = Map.of(
            "MINI_MART", new StarterSet("MINI_MART", List.of(PCS, BOX, PACK, BTL, CAN, CASE, KG, L),
                    List.of(new CategorySeed("Drinks", "ភេសជ្ជៈ"), new CategorySeed("Snacks", "នំចំណី"),
                            new CategorySeed("Noodles and rice", "មី និងអង្ករ"), new CategorySeed("Household", "របស់ប្រើប្រាស់ក្នុងផ្ទះ"),
                            new CategorySeed("Personal care", "ថែរក្សាខ្លួន")),
                    List.of(p("Drinking water 500ml", "ទឹកសុទ្ធ ៥០០មល", 0, "btl", "0.50", "24", false, "case", 24, "10.00"),
                            p("Soft drink can 330ml", "ភេសជ្ជៈកំប៉ុង ៣៣០មល", 0, "can", "0.75", "24", true, "case", 24, "16.00"),
                            p("Instant noodles", "មីកញ្ចប់", 2, "pack", "0.40", "30", true, "box", 30, "10.50"),
                            p("Potato chips", "ដំឡូងបារាំងចៀន", 1, "pack", "1.00", "10", true),
                            p("Dish soap 500ml", "សាប៊ូលាងចាន ៥០០មល", 3, "btl", "1.50", "6", false),
                            p("Toothpaste", "ថ្នាំដុសធ្មេញ", 4, "pcs", "1.80", "6", false))),
            "GROCERY", new StarterSet("GROCERY", List.of(PCS, KG, BAG, BTL, PACK, TRAY, BOX, L),
                    List.of(new CategorySeed("Vegetables and fruit", "បន្លែ និងផ្លែឈើ"), new CategorySeed("Meat and fish", "សាច់ និងត្រី"),
                            new CategorySeed("Rice and dry food", "អង្ករ និងម្ហូបស្ងួត"), new CategorySeed("Sauces and spices", "ទឹកជ្រលក់ និងគ្រឿងទេស"),
                            new CategorySeed("Drinks", "ភេសជ្ជៈ")),
                    List.of(p("Jasmine rice", "អង្ករផ្កាម្លិះ", 2, "kg", "1.20", "50", false, "bag", 25, "28.00"),
                            p("Chicken eggs", "ពងមាន់", 1, "pcs", "0.15", "60", true, "tray", 30, "4.20"),
                            p("Fish sauce 700ml", "ទឹកត្រី ៧០០មល", 3, "btl", "1.30", "12", false),
                            p("Sugar", "ស្ករស", 2, "kg", "1.00", "20", false),
                            p("Morning glory", "ត្រកួន", 0, "kg", "0.80", "5", true))),
            "PHARMACY", new StarterSet("PHARMACY", List.of(TAB, STRIP, BOX, BTL, TUBE, PCS, PACK),
                    List.of(new CategorySeed("Pain and fever", "ឈឺចាប់ និងគ្រុនក្តៅ"), new CategorySeed("Cold and flu", "ផ្តាសាយ"),
                            new CategorySeed("Vitamins", "វីតាមីន"), new CategorySeed("First aid", "សង្គ្រោះបឋម"),
                            new CategorySeed("Personal care", "ថែរក្សាខ្លួន")),
                    List.of(p("Paracetamol 500mg", "ប៉ារ៉ាសេតាមុល ៥០០មគ", 0, "strip", "0.50", "20", true, "box", 10, "4.50"),
                            p("Cough syrup 100ml", "ថ្នាំក្អក ១០០មល", 1, "btl", "2.50", "6", true),
                            p("Vitamin C 1000mg", "វីតាមីន C ១០០០មគ", 2, "tube", "3.00", "5", true),
                            p("Bandages", "បង់រុំរបួស", 3, "box", "1.20", "5", false),
                            p("Oral rehydration salts", "អំបិលបំប៉នជាតិទឹក", 0, "pack", "0.30", "20", true))),
            "COSMETICS", new StarterSet("COSMETICS", List.of(PCS, BTL, TUBE, BOX, SET),
                    List.of(new CategorySeed("Skin care", "ថែស្បែក"), new CategorySeed("Hair care", "ថែសក់"),
                            new CategorySeed("Makeup", "គ្រឿងសំអាង"), new CategorySeed("Fragrance", "ទឹកអប់"),
                            new CategorySeed("Body care", "ថែរាងកាយ")),
                    List.of(p("Sunscreen SPF50", "ឡេការពារកម្តៅថ្ងៃ SPF50", 0, "tube", "8.50", "4", true),
                            p("Shampoo 400ml", "សាប៊ូកក់សក់ ៤០០មល", 1, "btl", "4.20", "6", true),
                            p("Lipstick", "ក្រែមលាបមាត់", 2, "pcs", "6.00", "4", true),
                            p("Body lotion", "ឡេលាបខ្លួន", 4, "btl", "5.50", "4", true))),
            "HARDWARE", new StarterSet("HARDWARE", List.of(PCS, BOX, KG, M, ROLL, BAG, SET, L),
                    List.of(new CategorySeed("Tools", "ឧបករណ៍"), new CategorySeed("Paint", "ថ្នាំលាប"),
                            new CategorySeed("Electrical", "គ្រឿងអគ្គិសនី"), new CategorySeed("Plumbing", "បំពង់ទឹក"),
                            new CategorySeed("Building materials", "សម្ភារៈសំណង់")),
                    List.of(p("Claw hammer", "ញញួរ", 0, "pcs", "6.50", "3", false),
                            p("Screws 4x40mm", "ខ្ចៅ ៤x៤០មម", 0, "box", "2.00", "10", false),
                            p("Electric wire 2x1.5mm", "ខ្សែភ្លើង ២x១.៥មម", 2, "m", "0.45", "50", false, "roll", 100, "40.00"),
                            p("White paint 5L", "ថ្នាំលាបពណ៌ស ៥លីត្រ", 1, "pcs", "18.00", "4", false),
                            p("Cement 50kg", "ស៊ីម៉ងត៍ ៥០គ.ក", 4, "bag", "6.20", "20", false))),
            "PHONE_SHOP", new StarterSet("PHONE_SHOP", List.of(PCS, BOX, SET),
                    List.of(new CategorySeed("Phones", "ទូរស័ព្ទ"), new CategorySeed("Chargers and cables", "ឆ្នាំងសាក និងខ្សែ"),
                            new CategorySeed("Cases and screen protectors", "ស្រោម និងកញ្ចក់ការពារ"), new CategorySeed("Headphones", "កាស"),
                            new CategorySeed("Top-up cards", "កាតបញ្ចូលលុយ")),
                    List.of(p("USB-C cable 1m", "ខ្សែ USB-C ១ម", 1, "pcs", "3.00", "10", false),
                            p("Fast charger 20W", "ឆ្នាំងសាករហ័ស ២០W", 1, "pcs", "9.00", "5", false),
                            p("Tempered glass", "កញ្ចក់ការពារអេក្រង់", 2, "pcs", "2.50", "10", false),
                            p("Wireless earbuds", "កាសឥតខ្សែ", 3, "pcs", "19.00", "3", false),
                            p("Top-up card $5", "កាតបញ្ចូលលុយ $5", 4, "pcs", "5.00", "20", false))),
            "WHOLESALE", new StarterSet("WHOLESALE", List.of(PCS, CASE, BOX, PACK, BAG, KG, L, BTL, CAN),
                    List.of(new CategorySeed("Drinks", "ភេសជ្ជៈ"), new CategorySeed("Dry food", "ម្ហូបស្ងួត"),
                            new CategorySeed("Household", "របស់ប្រើប្រាស់ក្នុងផ្ទះ"), new CategorySeed("Personal care", "ថែរក្សាខ្លួន")),
                    List.of(p("Drinking water 500ml", "ទឹកសុទ្ធ ៥០០មល", 0, "btl", "0.40", "240", false, "case", 24, "8.50"),
                            p("Instant noodles", "មីកញ្ចប់", 1, "pack", "0.32", "300", true, "box", 30, "9.00"),
                            p("Washing powder 1kg", "ម្សៅសាប៊ូបោកខោអាវ ១គ.ក", 2, "bag", "2.20", "20", false, "case", 12, "25.00"),
                            p("Bath soap", "សាប៊ូដុសខ្លួន", 3, "pcs", "0.60", "48", false, "box", 48, "26.00"))),
            "OTHER", new StarterSet("OTHER", List.of(PCS, BOX, PACK, BTL, CAN, KG, L),
                    List.of(new CategorySeed("General", "ទូទៅ")),
                    List.of(p("Sample product", "ផលិតផលគំរូ", 0, "pcs", "1.00", "5", false))));

    private StarterSets() {
    }

    /** The set for a business type; an unknown type gets the general one. */
    public static StarterSet forType(String type) {
        return SETS.getOrDefault(type == null ? "OTHER" : type, SETS.get("OTHER"));
    }
}
