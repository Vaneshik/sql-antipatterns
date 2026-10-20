SELECT c.id, c.name
FROM customer c
LEFT JOIN orders o ON o.customer_id = c.id
WHERE o.id IS NULL;
