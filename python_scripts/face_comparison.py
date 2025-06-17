import sys
import json
from deepface import DeepFace
import logging

def compare_faces(img1_path, img2_path):
    try:
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
