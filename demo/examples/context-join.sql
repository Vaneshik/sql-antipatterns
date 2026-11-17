SELECT o.id, o.status
FROM orders o
LEFT JOIN customer c ON c.id = o.customer_id OR c.id = o.id
WHERE o.customer_id = o.id;
