-- A photo of the paper (FR-2.10, FR-2.14): the receipt of an expense, the supplier's invoice on a bill. Kept in the
-- private "documents" folder and only served to people who may see the expense or the bill.
ALTER TABLE expenses ADD COLUMN photo_path VARCHAR(255);
ALTER TABLE bills ADD COLUMN photo_path VARCHAR(255);
