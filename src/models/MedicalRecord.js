import mongoose from 'mongoose';

const medicalRecordSchema = new mongoose.Schema(
  {
    patientId: { type: mongoose.Schema.Types.ObjectId, ref: 'Patient', required: true },
    appointmentId: { type: mongoose.Schema.Types.ObjectId, ref: 'Appointment', required: true, unique: true },
    checkinId: { type: mongoose.Schema.Types.ObjectId, ref: 'Checkin', default: null },
    visitDate: { type: Date, required: true },
    diagnosis: { type: String, default: null },
    pulse: { type: String, default: null },
    bloodPressure: { type: String, default: null },
    treatmentNotes: {
      acupuncture: { type: String, default: null },
      acupressure: { type: String, default: null },
      other: { type: String, default: null },
    },
    medicalHistory: { type: String, default: null },
    followUpDate: { type: Date, default: null },
    doctorName: { type: String, default: null },
    editHistory: [
      {
        editedBy: { type: mongoose.Schema.Types.ObjectId, ref: 'User' },
        editedByName: String,
        editedAt: Date,
        changes: Object,
        _id: false,
      },
    ],
    recordedBy: { type: mongoose.Schema.Types.ObjectId, ref: 'User', required: true },
  },
  { timestamps: true },
);

medicalRecordSchema.index({ patientId: 1, visitDate: -1 });

export const MedicalRecord = mongoose.model('MedicalRecord', medicalRecordSchema, 'medicalRecords');
