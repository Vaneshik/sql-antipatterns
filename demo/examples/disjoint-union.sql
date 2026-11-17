SELECT id FROM customer WHERE id < 0
UNION
SELECT id FROM customer WHERE id >= 0;
