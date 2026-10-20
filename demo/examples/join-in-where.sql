SELECT c.name, o.status
FROM customer c, orders o
WHERE c.id = o.customer_id;
