SELECT c.id, o.status
FROM orders o
RIGHT JOIN customer c ON o.customer_id = c.id
WHERE (o.id > 0) IS TRUE;
