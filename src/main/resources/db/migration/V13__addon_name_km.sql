-- Add-on names in Khmer too, like products and roles; the app shows the one for the member's language.
ALTER TABLE addons ADD COLUMN name_km VARCHAR(60);

UPDATE addons SET name_km = CASE code
    WHEN 'EXTRA_BRANCH'    THEN 'សាខាបន្ថែម'
    WHEN 'EXTRA_MEMBERS'   THEN 'សមាជិក 5 នាក់បន្ថែម'
    WHEN 'EXTRA_PRODUCTS'  THEN 'ផលិតផល 1,000 បន្ថែម'
    WHEN 'TELEGRAM_ALERTS' THEN 'ការជូនដំណឹង Telegram ភ្លាមៗ'
    WHEN 'EXCEL_EXPORT'    THEN 'នាំចេញ Excel'
    WHEN 'TAX_INVOICE'     THEN 'វិក្កយបត្រអាករ'
END;
