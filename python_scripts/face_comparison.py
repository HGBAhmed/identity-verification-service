import sys
import json
import logging
from deepface import DeepFace

# Cache global DeepFace
_DEEPFACE_MODEL_LOADED = False

def initialize_deepface():
    """Pré-chargement DeepFace"""
    global _DEEPFACE_MODEL_LOADED

    if not _DEEPFACE_MODEL_LOADED:
        print("Pré-chargement DeepFace VGG-Face")
        try:
            # Forcer le chargement du modèle avec une vérification dummy
            # Cela charge et cache le modèle en mémoire
            import numpy as np
            import os

            # Créer une image dummy temporaire pour forcer le chargement
            dummy_image = np.ones((224, 224, 3), dtype=np.uint8) * 128
            temp_path = "/tmp/dummy_face_init.jpg" if os.name != 'nt' else "dummy_face_init.jpg"

            from PIL import Image
            Image.fromarray(dummy_image).save(temp_path)

            # Forcer l'initialisation du modèle
            try:
                DeepFace.verify(temp_path, temp_path,
                                model_name='VGG-Face',
                                distance_metric='cosine',
                                enforce_detection=False)  # Pas besoin de détecter les visages
                print("Modèle DeepFace VGG-Face pré-chargé avec succès")
            except:
                # Même en cas d'erreur, le modèle est probablement chargé
                print("Modèle DeepFace initialisé")
            finally:
                # Nettoyer le fichier temporaire
                try:
                    os.remove(temp_path)
                except:
                    pass

            _DEEPFACE_MODEL_LOADED = True

        except Exception as e:
            print(f"Avertissement pré-chargement DeepFace: {e}")
            # On continue même en cas d'erreur de pré-chargement
            _DEEPFACE_MODEL_LOADED = True


def compare_faces(img1_path, img2_path, custom_threshold=None):
    # S'assurer que le modèle est chargé
    initialize_deepface()

    try:
        # Le modèle VGG-Face est maintenant déjà en mémoire !
        result = DeepFace.verify(img1_path, img2_path,
                                 model_name='VGG-Face',
                                 distance_metric='cosine')

        threshold_to_use = custom_threshold if custom_threshold is not None else result['threshold']
        is_verified = result['distance'] <= threshold_to_use

        return {
            'verified': is_verified,
            'distance': result['distance'],
            'confidence': 1 - result['distance'],
            'threshold': threshold_to_use,
            'default_threshold': result['threshold'],
            'custom_threshold_used': custom_threshold is not None,
            'status': 'success'
        }
    except Exception as e:
        return {
            'verified': False,
            'distance': 1.0,
            'confidence': 0.0,
            'threshold': custom_threshold or 0.68,
            'error': str(e),
            'status': 'error'
        }

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print(json.dumps({'error': 'Usage: python face_comparison.py <img1_path> <img2_path> [threshold]'}))
        sys.exit(1)

    img1_path = sys.argv[1]
    img2_path = sys.argv[2]

    custom_threshold = None
    if len(sys.argv) >= 4:
        try:
            custom_threshold = float(sys.argv[3])
            if not (0.0 <= custom_threshold <= 1.0):
                custom_threshold = None
        except ValueError:
            custom_threshold = None

    result = compare_faces(img1_path, img2_path, custom_threshold)
    print(json.dumps(result))