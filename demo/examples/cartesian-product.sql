SELECT c.name, o.status
FROM customer c
CROSS JOIN orders o;
