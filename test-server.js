// Simple Node.js test server for image uploads
// Install: npm install express multer
// Run: node test-server.js

const express = require('express');
const multer = require('multer');
const path = require('path');
const fs = require('fs');

const app = express();
const PORT = 8080;

// Create uploads directory
const UPLOAD_DIR = path.join(__dirname, 'uploads');
if (!fs.existsSync(UPLOAD_DIR)) {
    fs.mkdirSync(UPLOAD_DIR);
}

// Configure multer for file uploads
const storage = multer.diskStorage({
    destination: (req, file, cb) => {
        cb(null, UPLOAD_DIR);
    },
    filename: (req, file, cb) => {
        const timestamp = req.body.timestamp || Date.now();
        const ext = path.extname(file.originalname);
        cb(null, `glass_${timestamp}${ext}`);
    }
});

const upload = multer({ 
    storage: storage,
    limits: { fileSize: 10 * 1024 * 1024 } // 10MB limit
});

// Upload endpoint
app.post('/upload', upload.single('image'), (req, res) => {
    if (!req.file) {
        return res.status(400).json({ 
            success: false, 
            error: 'No image file provided' 
        });
    }

    console.log('📸 Image uploaded:');
    console.log('  - Filename:', req.file.filename);
    console.log('  - Size:', req.file.size, 'bytes');
    console.log('  - Timestamp:', req.body.timestamp);
    console.log('  - Source:', req.body.source);
    console.log('  - Saved to:', req.file.path);

    res.json({
        success: true,
        filename: req.file.filename,
        size: req.file.size,
        path: `/uploads/${req.file.filename}`
    });
});

// Serve uploaded images
app.use('/uploads', express.static(UPLOAD_DIR));

// List uploaded images
app.get('/list', (req, res) => {
    fs.readdir(UPLOAD_DIR, (err, files) => {
        if (err) {
            return res.status(500).json({ error: 'Failed to list files' });
        }
        res.json({ files: files });
    });
});

// Health check
app.get('/', (req, res) => {
    res.json({ 
        status: 'ok', 
        message: 'Image upload server running',
        uploadCount: fs.readdirSync(UPLOAD_DIR).length
    });
});

app.listen(PORT, '0.0.0.0', () => {
    console.log(`✅ Image upload server running on http://0.0.0.0:${PORT}`);
    console.log(`📁 Upload directory: ${UPLOAD_DIR}`);
    console.log(`\nFor Android emulator, use: http://10.0.2.2:${PORT}/upload`);
});
