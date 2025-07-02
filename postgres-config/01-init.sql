-- Script d'initialisation PostgreSQL
-- Créer et configurer l'utilisateur postgres avec le bon mot de passe

-- S'assurer que l'utilisateur postgres existe avec le bon mot de passe
ALTER USER postgres PASSWORD 'password';

-- Créer la base de données si elle n'existe pas
SELECT 'CREATE DATABASE identity_verification'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'identity_verification')\gexec

-- Accorder tous les privilèges
GRANT ALL PRIVILEGES ON DATABASE identity_verification TO postgres;

-- Configuration des connexions
-- pg_hba.conf sera automatiquement configuré par les variables d'environnement