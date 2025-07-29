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


def compare_faces(img1_path, img2_path):
    # S'assurer que le modèle est chargé
    initialize_deepface()

    try:
        # Le modèle VGG-Face est maintenant déjà en mémoire !
        result = DeepFace.verify(img1_path, img2_path,
                                 model_name='VGG-Face',
                                 distance_metric='cosine')

        return {
            'verified': result['verified'],
            'distance': result['distance'],
            'confidence': 1 - result['distance'],
            'threshold': result['threshold'],
            'status': 'success'
        }
    except Exception as e:
        return {
            'verified': False,
            'distance': 1.0,
            'confidence': 0.0,
            'error': str(e),
            'status': 'error'
        }

if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(json.dumps({'error': 'Usage: python face_comparison.py <img1_path> <img2_path>'}))
        sys.exit(1)

    img1_path = sys.argv[1]
    img2_path = sys.argv[2]

    result = compare_faces(img1_path, img2_path)
    print(json.dumps(result))