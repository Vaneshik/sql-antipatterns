-- 1. DISTINCT после JOIN → EXISTS
-- Несколько оплаченных заказов размножают строки клиентов.
SELECT DISTINCT c.id, c.name
FROM public.customer c
JOIN public.orders o ON o.customer_id = c.id
WHERE o.status = 'paid';

-- 2. Избыточный DISTINCT → убрать дедупликацию
SELECT DISTINCT id, name
FROM public.customer;

-- 3. LEFT JOIN → INNER JOIN
-- WHERE отбрасывает строки без совпадения справа.
SELECT c.id, c.name, o.status
FROM public.customer c
LEFT JOIN public.orders o ON o.customer_id = c.id
WHERE o.status = 'paid';

-- 4. Условие по ключу группировки → перенести из HAVING в WHERE
SELECT customer_id, COUNT(*) AS order_count
FROM public.orders
GROUP BY customer_id
HAVING customer_id > 1;

-- 5. Сравнение с NULL → проверить условие
SELECT id, name
FROM public.customer
WHERE name = NULL;

-- 6. Контрпример — DISTINCT сохраняется, потому что имена повторяются
SELECT DISTINCT c.name
FROM public.customer c
JOIN public.orders o ON o.customer_id = c.id
WHERE o.status = 'paid';

-- 7. Символьное доказательство уникальности при условии id > 0
SELECT DISTINCT ABS(id) AS positive_id
FROM public.customer
WHERE id > 0;

-- 8. Пустой результат из-за противоречия
SELECT id, name
FROM public.customer
WHERE id > 10 AND id < 5;

-- 9. Корректный вариант с EXISTS
SELECT c.id, c.name
FROM public.customer c
WHERE EXISTS (
    SELECT 1
    FROM public.orders o
    WHERE o.customer_id = c.id AND o.status = 'paid'
);
