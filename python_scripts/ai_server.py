import os
import sys
import json
import logging
import traceback
from typing import Dict, Any, Optional, Tuple
import time
from pathlib import Path

# Framework web
from flask import Flask, request, jsonify
from werkzeug.serving import run_simple
import threading

# Import des modules existants
script_dir = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, script_dir)

# Répertoire temporaire partagé
SHARED_TEMP_DIR = r"C:\Users\ahmed\Downloads\identity-verification-service\identity-verification-service\shared-temp"

# Créer le répertoire s'il n'existe pas
os.makedirs(SHARED_TEMP_DIR, exist_ok=True)

# Configuration logging améliorée
logging.basicConfig(
    level=logging.DEBUG,  # Plus de détails pour debug
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.StreamHandler(sys.stdout),
        logging.FileHandler('ai_server.log')  # Log dans un fichier aussi
    ]
)
logger = logging.getLogger(__name__)

class AIModelManager:
    """Gestionnaire des modèles IA avec chargement unique et gestion d'erreur robuste"""

    def __init__(self):
        self.models_loaded = False
        self.deepface_ready = False
        self.easyocr_reader = None
        self.load_lock = threading.Lock()
        self.initialization_error = None

        logger.info(" Initialisation AIModelManager")
        self._load_models()

    def _load_models(self):
        """Charge tous les modèles une seule fois avec gestion d'erreur robuste"""
        with self.load_lock:
            if self.models_loaded:
                logger.info(" Modèles déjà chargés")
                return

            logger.info(" Chargement des modèles IA...")
            start_time = time.time()

            try:
                # 1. Initialiser DeepFace
                logger.info(" Initialisation DeepFace...")
                self._initialize_deepface()
                logger.info(" DeepFace initialisé")

                # 2. Initialiser EasyOCR
                logger.info(" Initialisation EasyOCR...")
                self._initialize_easyocr()
                logger.info(" EasyOCR initialisé")

                # 3. Autres initialisations
                logger.info(" Vérification autres dépendances...")
                self._initialize_others()
                logger.info(" Autres dépendances vérifiées")

                self.models_loaded = True
                load_time = time.time() - start_time
                logger.info(f" Tous les modèles chargés avec succès en {load_time:.2f}s")

            except Exception as e:
                self.initialization_error = str(e)
                logger.error(f" Erreur chargement modèles: {e}")
                logger.error(f" Traceback: {traceback.format_exc()}")
                # Ne pas lever l'exception, continuer en mode dégradé
                logger.warning(" Serveur en mode dégradé")

    def _initialize_deepface(self):
        """Initialise DeepFace avec pré-chargement du modèle"""
        try:
            from deepface import DeepFace
            import numpy as np
            from PIL import Image

            logger.debug("Pré-chargement DeepFace VGG-Face...")

            # Créer une image dummy pour forcer le chargement du modèle
            dummy_image = np.ones((224, 224, 3), dtype=np.uint8) * 128
            temp_path = os.path.join(script_dir, "dummy_face_init.jpg")

            Image.fromarray(dummy_image).save(temp_path)

            try:
                # Forcer l'initialisation du modèle VGG-Face
                result = DeepFace.verify(temp_path, temp_path,
                                         model_name='VGG-Face',
                                         distance_metric='cosine',
                                         enforce_detection=False)
                logger.debug("DeepFace VGG-Face pré-chargé avec succès")
            except Exception as e:
                logger.debug(f"DeepFace initialisé (erreur dummy normale): {e}")
            finally:
                # Nettoyer
                try:
                    os.remove(temp_path)
                except:
                    pass

            self.deepface_ready = True
            logger.info(" DeepFace prêt")

        except Exception as e:
            logger.error(f" Erreur initialisation DeepFace: {e}")
            self.deepface_ready = False
            raise

    def _initialize_easyocr(self):
        """Initialise EasyOCR avec cache global"""
        try:
            import easyocr
            logger.debug("Création EasyOCR Reader...")

            self.easyocr_reader = easyocr.Reader(['en', 'fr'], gpu=False)
            logger.info(" EasyOCR Reader créé et mis en cache")

        except Exception as e:
            logger.error(f" Erreur initialisation EasyOCR: {e}")
            self.easyocr_reader = None
            raise

    def _initialize_others(self):
        """Initialise autres dépendances"""
        try:
            # Import pour s'assurer que tout est disponible
            import passporteye
            logger.debug(" PassportEye disponible")

            import cv2
            logger.debug(" OpenCV disponible")

            import tensorflow
            logger.debug(" TensorFlow disponible")

            logger.info(" Toutes les dépendances sont disponibles")

        except ImportError as e:
            logger.warning(f"️ Dépendance manquante: {e}")
            # Ne pas lever l'exception pour les dépendances optionnelles

class DocumentProcessor:
    """Processeur de documents réutilisant les modèles chargés"""

    def __init__(self, model_manager: AIModelManager):
        self.model_manager = model_manager
        self.extract_document_data = None
        self.extract_document_both_sides = None

        # Import du module d'extraction existant avec gestion d'erreur
        try:
            logger.info(" Import du module document_extractor...")

            # Méthode plus robuste d'import
            import importlib.util
            extractor_path = os.path.join(script_dir, "document_extractor.py")

            if os.path.exists(extractor_path):
                spec = importlib.util.spec_from_file_location("document_extractor", extractor_path)
                document_extractor = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(document_extractor)

                self.extract_document_data = document_extractor.extract_document_data
                self.extract_document_both_sides = document_extractor.extract_document_both_sides

                logger.info("Module document_extractor importé avec succès")
            else:
                logger.error(f" Fichier document_extractor.py non trouvé: {extractor_path}")
                raise ImportError(f"document_extractor.py non trouvé")

        except Exception as e:
            logger.error(f" Erreur import document_extractor: {e}")
            logger.error(f" Traceback: {traceback.format_exc()}")
            raise

    def process_single_document(self, image_path: str, expected_side: str = None) -> Dict[str, Any]:
        """Traite un document unique"""
        try:
            logger.info(f" Traitement document: {image_path}")

            if not self.extract_document_data:
                raise RuntimeError("Module d'extraction non initialisé")

            # Utiliser le reader EasyOCR global si disponible
            if self.model_manager.easyocr_reader:
                # Monkey patch pour utiliser notre reader
                try:
                    import document_extractor
                    document_extractor._EASYOCR_READER_CACHE = self.model_manager.easyocr_reader
                    logger.debug("EasyOCR reader global configuré")
                except:
                    logger.debug("Impossible de configurer EasyOCR reader global")

            # Appeler la fonction d'extraction
            if expected_side:
                result = self.extract_document_data(image_path, expected_side)
            else:
                result = self.extract_document_data(image_path)

            logger.info(f" Document traité - Status: {result.get('status', 'unknown')}")
            return result

        except Exception as e:
            logger.error(f" Erreur traitement document: {e}")
            logger.error(f" Traceback: {traceback.format_exc()}")
            return {
                'status': 'error',
                'error': str(e),
                'traceback': traceback.format_exc()
            }

    def process_recto_verso(self, recto_path: str, verso_path: str) -> Dict[str, Any]:
        """Traite recto/verso d'une carte d'identité"""
        try:
            logger.info(f" Traitement recto/verso: {recto_path} + {verso_path}")

            if not self.extract_document_both_sides:
                raise RuntimeError("Module d'extraction recto/verso non initialisé")

            # Utiliser le reader EasyOCR global si disponible
            if self.model_manager.easyocr_reader:
                try:
                    import document_extractor
                    document_extractor._EASYOCR_READER_CACHE = self.model_manager.easyocr_reader
                    logger.debug("EasyOCR reader global configuré pour recto/verso")
                except:
                    logger.debug("Impossible de configurer EasyOCR reader global pour recto/verso")

            result = self.extract_document_both_sides(recto_path, verso_path)

            logger.info(f" Recto/verso traité - Status: {result.get('status', 'unknown')}")
            return result

        except Exception as e:
            logger.error(f" Erreur traitement recto/verso: {e}")
            logger.error(f" Traceback: {traceback.format_exc()}")
            return {
                'status': 'error',
                'error': str(e),
                'traceback': traceback.format_exc()
            }

class FaceComparator:
    """Comparateur de visages réutilisant DeepFace chargé"""

    def __init__(self, model_manager: AIModelManager):
        self.model_manager = model_manager

    def compare_faces(self, img1_path: str, img2_path: str,
                      custom_threshold: Optional[float] = None) -> Dict[str, Any]:
        """Compare deux visages"""
        try:
            if not self.model_manager.deepface_ready:
                raise RuntimeError("DeepFace non initialisé")

            logger.info(f"👥 Comparaison faciale: {img1_path} vs {img2_path}")

            from deepface import DeepFace

            # Le modèle VGG-Face est déjà en mémoire !
            result = DeepFace.verify(img1_path, img2_path,
                                     model_name='VGG-Face',
                                     distance_metric='cosine')

            threshold_to_use = custom_threshold if custom_threshold is not None else result['threshold']
            is_verified = result['distance'] <= threshold_to_use

            comparison_result = {
                'verified': bool(is_verified),
                'distance': float(result['distance']),
                'confidence': float(1 - result['distance']),
                'threshold': float(threshold_to_use),
                'default_threshold': float(result['threshold']),
                'custom_threshold_used': bool(custom_threshold is not None),
                'status': 'success'
            }

            logger.info(f" Comparaison terminée - Match: {is_verified}, Confiance: {comparison_result['confidence']:.3f}")
            return comparison_result

        except Exception as e:
            logger.error(f" Erreur comparaison faciale: {e}")
            logger.error(f" Traceback: {traceback.format_exc()}")
            return {
                'verified': False,
                'distance': 1.0,
                'confidence': 0.0,
                'threshold': custom_threshold or 0.68,
                'error': str(e),
                'status': 'error',
                'traceback': traceback.format_exc()
            }

# Application Flask
app = Flask(__name__)
app.config['MAX_CONTENT_LENGTH'] = 100 * 1024 * 1024  # 100MB

# Gestionnaires globaux
model_manager = None
document_processor = None
face_comparator = None
services_initialized = False
init_lock = threading.Lock()

def initialize_services():
    """Initialise les services de façon thread-safe avec gestion d'erreur robuste"""
    global model_manager, document_processor, face_comparator, services_initialized

    with init_lock:
        if services_initialized:
            logger.debug("Services déjà initialisés")
            return True

        logger.info(" Initialisation des services...")

        try:
            # 1. Initialiser le gestionnaire de modèles
            logger.info("Initialisation AIModelManager...")
            model_manager = AIModelManager()

            # 2. Initialiser le processeur de documents
            logger.info(" Initialisation DocumentProcessor...")
            document_processor = DocumentProcessor(model_manager)

            # 3. Initialiser le comparateur de visages
            logger.info(" Initialisation FaceComparator...")
            face_comparator = FaceComparator(model_manager)

            services_initialized = True
            logger.info(" Tous les services sont initialisés avec succès!")
            return True

        except Exception as e:
            logger.error(f" Erreur critique lors de l'initialisation des services: {e}")
            logger.error(f" Traceback: {traceback.format_exc()}")

            # Réinitialiser les variables globales en cas d'erreur
            model_manager = None
            document_processor = None
            face_comparator = None
            services_initialized = False

            return False

@app.before_request
def ensure_services_initialized():
    """S'assure que les services sont initialisés avant chaque requête"""
    if not services_initialized:
        logger.debug(" Services non initialisés, initialisation en cours...")
        success = initialize_services()
        if not success:
            logger.error(" Impossible d'initialiser les services")
            # On continue quand même pour permettre les endpoints de santé

@app.route('/health', methods=['GET'])
def health_check():
    """Endpoint de santé avec informations détaillées"""
    try:
        health_data = {
            'status': 'healthy' if services_initialized else 'degraded',
            'timestamp': time.time(),
            'services_initialized': services_initialized,
            'models_loaded': model_manager.models_loaded if model_manager else False,
            'deepface_ready': model_manager.deepface_ready if model_manager else False,
            'easyocr_ready': model_manager.easyocr_reader is not None if model_manager else False,
            'document_processor_ready': document_processor is not None,
            'face_comparator_ready': face_comparator is not None,
            'endpoints': [
                'GET /health',
                'GET /status',
                'POST /extract/document',
                'POST /extract/recto-verso',
                'POST /compare/faces'
            ]
        }

        if model_manager and model_manager.initialization_error:
            health_data['initialization_error'] = model_manager.initialization_error

        status_code = 200 if services_initialized else 503
        logger.info(f"Health check - Status: {health_data['status']}")

        return jsonify(health_data), status_code

    except Exception as e:
        logger.error(f" Erreur health check: {e}")
        return jsonify({
            'status': 'error',
            'error': str(e),
            'timestamp': time.time()
        }), 500

@app.route('/extract/document', methods=['POST'])
def extract_document():
    """Endpoint extraction document unique avec validation robuste"""
    try:
        logger.info(" Requête reçue: /extract/document")

        if not services_initialized or not document_processor:
            logger.error(" Services non initialisés pour extraction document")
            return jsonify({
                'status': 'error',
                'error': 'Services IA non disponibles'
            }), 503

        data = request.get_json()
        logger.debug(f" Données reçues: {data}")

        if not data or 'image_path' not in data:
            logger.warning(" Paramètre image_path manquant")
            return jsonify({
                'status': 'error',
                'error': 'Paramètre image_path requis'
            }), 400

        image_path = data['image_path']
        expected_side = data.get('expected_side')

        # Convertir les chemins relatifs en absolus
        if not os.path.isabs(image_path):
            # Si c'est un chemin relatif, le chercher dans shared-temp
            absolute_path = os.path.join(SHARED_TEMP_DIR, image_path)
            logger.info(f"Chemin relatif détecté, conversion: {image_path} -> {absolute_path}")
            image_path = absolute_path

        logger.info(f" Traitement demandé: {image_path} (side: {expected_side})")

        if not os.path.exists(image_path):
            logger.error(f" Fichier non trouvé: {image_path}")
            return jsonify({
                'status': 'error',
                'error': f'Fichier non trouvé: {image_path}'
            }), 404

        result = document_processor.process_single_document(image_path, expected_side)

        logger.info(f" Traitement terminé - Status: {result.get('status')}")

        status_code = 200 if result.get('status') == 'success' else 400
        return jsonify(result), status_code

    except Exception as e:
        logger.error(f" Erreur endpoint extract_document: {e}")
        logger.error(f" Traceback: {traceback.format_exc()}")
        return jsonify({
            'status': 'error',
            'error': str(e),
            'traceback': traceback.format_exc()
        }), 500

@app.route('/extract/recto-verso', methods=['POST'])
def extract_recto_verso():
    """Endpoint extraction recto/verso avec validation robuste"""
    try:
        logger.info(" Requête reçue: /extract/recto-verso")

        if not services_initialized or not document_processor:
            logger.error(" Services non initialisés pour extraction recto/verso")
            return jsonify({
                'status': 'error',
                'error': 'Services IA non disponibles'
            }), 503

        data = request.get_json()
        logger.debug(f" Données reçues: {data}")

        if not data or 'recto_path' not in data or 'verso_path' not in data:
            logger.warning(" Paramètres recto_path/verso_path manquants")
            return jsonify({
                'status': 'error',
                'error': 'Paramètres recto_path et verso_path requis'
            }), 400

        recto_path = data['recto_path']
        verso_path = data['verso_path']

        logger.info(f" Traitement recto/verso demandé: {recto_path} + {verso_path}")

        if not os.path.exists(recto_path):
            logger.error(f" Fichier recto non trouvé: {recto_path}")
            return jsonify({
                'status': 'error',
                'error': f'Fichier recto non trouvé: {recto_path}'
            }), 404

        if not os.path.exists(verso_path):
            logger.error(f" Fichier verso non trouvé: {verso_path}")
            return jsonify({
                'status': 'error',
                'error': f'Fichier verso non trouvé: {verso_path}'
            }), 404

        result = document_processor.process_recto_verso(recto_path, verso_path)

        logger.info(f" Traitement recto/verso terminé - Status: {result.get('status')}")

        status_code = 200 if result.get('status') == 'success' else 400
        return jsonify(result), status_code

    except Exception as e:
        logger.error(f" Erreur endpoint extract_recto_verso: {e}")
        logger.error(f" Traceback: {traceback.format_exc()}")
        return jsonify({
            'status': 'error',
            'error': str(e),
            'traceback': traceback.format_exc()
        }), 500

@app.route('/compare/faces', methods=['POST'])
def compare_faces():
    """Endpoint comparaison faciale avec validation robuste"""
    try:
        logger.info(" Requête reçue: /compare/faces")

        if not services_initialized or not face_comparator:
            logger.error("Services non initialisés pour comparaison faciale")
            return jsonify({
                'status': 'error',
                'error': 'Services IA non disponibles'
            }), 503

        data = request.get_json()
        logger.debug(f" Données reçues: {data}")

        if not data or 'img1_path' not in data or 'img2_path' not in data:
            logger.warning(" Paramètres img1_path/img2_path manquants")
            return jsonify({
                'status': 'error',
                'error': 'Paramètres img1_path et img2_path requis'
            }), 400

        img1_path = data['img1_path']
        img2_path = data['img2_path']
        custom_threshold = data.get('threshold')
        print(f"Threshold reçu: {custom_threshold}")

        logger.info(f" Comparaison faciale demandée: {img1_path} vs {img2_path}")

        if not os.path.exists(img1_path):
            logger.error(f"Image 1 non trouvée: {img1_path}")
            return jsonify({
                'status': 'error',
                'error': f'Image 1 non trouvée: {img1_path}'
            }), 404

        if not os.path.exists(img2_path):
            logger.error(f" Image 2 non trouvée: {img2_path}")
            return jsonify({
                'status': 'error',
                'error': f'Image 2 non trouvée: {img2_path}'
            }), 404

        if custom_threshold is not None:
            try:
                custom_threshold = float(custom_threshold)
                if not (0.0 <= custom_threshold <= 1.0):
                    logger.warning(f"Seuil invalide: {custom_threshold}")
                    custom_threshold = None
            except (ValueError, TypeError):
                logger.warning(f" Seuil non numérique: {custom_threshold}")
                custom_threshold = None

        result = face_comparator.compare_faces(img1_path, img2_path, custom_threshold)
        print("Résultat face_comparator:", result)
        logger.info(f" Comparaison terminée - Status: {result.get('status')}")

        status_code = 200 if result.get('status') == 'success' else 400
        return jsonify(result), status_code

    except Exception as e:
        logger.error(f" Erreur endpoint compare_faces: {e}")
        logger.error(f" Traceback: {traceback.format_exc()}")
        return jsonify({
            'status': 'error',
            'error': str(e),
            'traceback': traceback.format_exc()
        }), 500

@app.route('/status', methods=['GET'])
def get_status():
    """Endpoint de statut détaillé"""
    try:
        uptime = time.time() - start_time if 'start_time' in globals() else 0

        status_data = {
            'status': 'running',
            'services_initialized': services_initialized,
            'uptime_seconds': uptime,
            'models': {
                'loaded': model_manager.models_loaded if model_manager else False,
                'deepface_ready': model_manager.deepface_ready if model_manager else False,
                'easyocr_ready': model_manager.easyocr_reader is not None if model_manager else False
            },
            'processors': {
                'document_processor_ready': document_processor is not None,
                'face_comparator_ready': face_comparator is not None
            },
            'endpoints_available': [
                'GET /health',
                'GET /status',
                'POST /extract/document',
                'POST /extract/recto-verso',
                'POST /compare/faces'
            ]
        }

        if model_manager and model_manager.initialization_error:
            status_data['initialization_error'] = model_manager.initialization_error

        return jsonify(status_data), 200

    except Exception as e:
        logger.error(f"Erreur status: {e}")
        return jsonify({
            'status': 'error',
            'error': str(e)
        }), 500

@app.errorhandler(413)
def too_large(e):
    """Gestionnaire d'erreur pour fichiers trop volumineux"""
    logger.warning(" Fichier trop volumineux reçu")
    return jsonify({
        'status': 'error',
        'error': 'Fichier trop volumineux'
    }), 413

@app.errorhandler(404)
def not_found(error):
    """Gestionnaire d'erreur 404 avec aide"""
    logger.warning(f"Endpoint non trouvé: {request.path}")
    return jsonify({
        'status': 'error',
        'error': f'Endpoint non trouvé: {request.path}',
        'available_endpoints': [
            'GET /health - Santé du serveur',
            'GET /status - Statut détaillé',
            'POST /extract/document - Extraction document unique',
            'POST /extract/recto-verso - Extraction recto/verso',
            'POST /compare/faces - Comparaison faciale'
        ]
    }), 404

@app.errorhandler(500)
def internal_error(error):
    """Gestionnaire d'erreur 500"""
    logger.error(f"Erreur interne: {error}")
    return jsonify({
        'status': 'error',
        'error': 'Erreur interne du serveur'
    }), 500

if __name__ == '__main__':
    import argparse

    parser = argparse.ArgumentParser(description='Serveur IA pour vérification identité')
    parser.add_argument('--host', default='127.0.0.1', help='Adresse d\'écoute')
    parser.add_argument('--port', default=5000, type=int, help='Port d\'écoute')
    parser.add_argument('--debug', action='store_true', help='Mode debug')
    parser.add_argument('--threaded', action='store_true', default=True, help='Mode multi-thread')

    args = parser.parse_args()

    global start_time
    start_time = time.time()

    logger.info("="*60)
    logger.info("DÉMARRAGE SERVEUR IA IDENTITY VERIFICATION")
    logger.info("="*60)
    logger.info(f"Répertoire de travail: {os.getcwd()}")
    logger.info(f"Script directory: {script_dir}")
    logger.info(f"Adresse: {args.host}:{args.port}")
    logger.info(f"Mode debug: {args.debug}")
    logger.info(f" Multi-thread: {args.threaded}")

    # Pré-initialiser les services au démarrage
    logger.info("Pré-initialisation des services...")
    initialize_services()

    logger.info("="*60)
    logger.info("ERVEUR PRÊT - En attente de requêtes...")
    logger.info("="*60)

    if args.debug:
        app.run(host=args.host, port=args.port, debug=True, threaded=args.threaded)
    else:
        # Serveur de production avec Werkzeug
        run_simple(args.host, args.port, app,
                   threaded=args.threaded,
                   use_reloader=False,
                   use_debugger=False)