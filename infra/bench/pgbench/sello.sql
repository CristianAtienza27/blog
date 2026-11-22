-- Consulta del filtro del sello (H7, 6.2.7): la que hace SecurityStampFilter en cada petición con
-- sesión, por clave primaria, como cms_api. {{usuario}}: el id del usuario de prueba (bench.mjs).
SELECT password_changed_at FROM app_user WHERE id = '{{usuario}}';
