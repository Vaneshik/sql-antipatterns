SELECT c.id, c.name, o.status
FROM customer c
LEFT JOIN orders o ON o.customer_id = c.id
WHERE o.status = 'paid';
