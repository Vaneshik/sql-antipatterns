SELECT o.id, o.status
FROM orders o
LEFT JOIN customer c ON c.id = o.customer_id;
